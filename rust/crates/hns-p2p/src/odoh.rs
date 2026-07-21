use crate::{
    DnsRelayPeerConnection, DnsRelayPeerConnector, P2pError, Packet, PeerManager, VersionPacket,
    prepare_dns_relay_query, validate_dns_relay_response,
};
use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes128Gcm as ResponseAes128Gcm, Nonce};
use hkdf::Hkdf;
use hns_core::hash::blake2b_256;
use hns_core::network::Network;
use hns_core::network_policy::is_publicly_routable;
use hpke::aead::{AeadCtxS, AesGcm128};
use hpke::kdf::HkdfSha256;
use hpke::kem::X25519HkdfSha256;
use hpke::{Deserializable, Kem as KemTrait, OpModeS, Serializable};
use k256::ecdsa::signature::hazmat::PrehashVerifier;
use k256::ecdsa::{Signature, VerifyingKey};
use sha2::Sha256;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use thiserror::Error;

pub const EXPERIMENTAL_ODOH_SERVICE: u64 = 0x2000_0000;
pub const EXPERIMENTAL_ODOH: u8 = 0xf2;
pub const MAX_ODOH_QUERY_SIZE: usize = 8192;
pub const MAX_ODOH_RESPONSE_SIZE: usize = u16::MAX as usize;
pub const MAX_ODOH_PACKET_SIZE: usize = 12 + 4 + MAX_ODOH_RESPONSE_SIZE + 2 + 4096;
pub const DEFAULT_ODOH_TIMEOUT: Duration = Duration::from_secs(10);

const ODOH_VERSION: u8 = 1;
const REQUEST_ID_SIZE: usize = 8;
const TARGET_CONFIG_TAG: &[u8] = b"HNS-P2P-ODOH-CONFIG-V1\0";
const QUERY_INFO: &[u8] = b"odoh query";
const RESPONSE_INFO: &[u8] = b"odoh response";
const KEY_ID_INFO: &[u8] = b"odoh key id";
const RESPONSE_KEY_INFO: &[u8] = b"odoh key";
const RESPONSE_NONCE_INFO: &[u8] = b"odoh nonce";
const SUPPORTED_CONFIG_VERSION: u16 = 1;
const KEM_X25519_SHA256: u16 = 0x0020;
const KDF_HKDF_SHA256: u16 = 0x0001;
const AEAD_AES_128_GCM: u16 = 0x0001;
const DIRECT_BRONTIDE: u8 = 1;
const MAX_CONFIG_SIZE: usize = 16_384;
const MAX_CONFIG_LIFETIME: u64 = 172_800;
const MAX_ADVISORY_PACKETS: usize = 32;
const ROLE_PROXY: u8 = 1;

type OdohSenderContext = AeadCtxS<AesGcm128, HkdfSha256, X25519HkdfSha256>;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum OdohOpcode {
    GetCaps = 0,
    Caps = 1,
    GetConfig = 2,
    Config = 3,
    ClientQuery = 4,
    TargetQuery = 5,
    TargetResponse = 6,
    ClientResponse = 7,
    Cancel = 8,
    Error = 9,
}

impl TryFrom<u8> for OdohOpcode {
    type Error = P2pError;

    fn try_from(value: u8) -> Result<Self, P2pError> {
        match value {
            0 => Ok(Self::GetCaps),
            1 => Ok(Self::Caps),
            2 => Ok(Self::GetConfig),
            3 => Ok(Self::Config),
            4 => Ok(Self::ClientQuery),
            5 => Ok(Self::TargetQuery),
            6 => Ok(Self::TargetResponse),
            7 => Ok(Self::ClientResponse),
            8 => Ok(Self::Cancel),
            9 => Ok(Self::Error),
            _ => Err(P2pError::InvalidOdohPacket("unknown opcode")),
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OdnsPacket {
    pub opcode: OdohOpcode,
    pub request_id: [u8; REQUEST_ID_SIZE],
    pub body: Vec<u8>,
}

impl OdnsPacket {
    pub fn new(
        opcode: OdohOpcode,
        request_id: [u8; REQUEST_ID_SIZE],
        body: Vec<u8>,
    ) -> Result<Self, P2pError> {
        let packet = Self {
            opcode,
            request_id,
            body,
        };
        packet.validate()?;
        Ok(packet)
    }

    pub fn encode(&self) -> Result<Vec<u8>, P2pError> {
        self.validate()?;
        let mut out = Vec::with_capacity(12 + self.body.len());
        out.push(ODOH_VERSION);
        out.push(self.opcode as u8);
        out.extend(0u16.to_le_bytes());
        out.extend(self.request_id);
        out.extend(&self.body);
        Ok(out)
    }

    pub fn decode(raw: &[u8]) -> Result<Self, P2pError> {
        if raw.len() < 12 {
            return Err(P2pError::InvalidOdohPacket("truncated envelope"));
        }
        if raw.len() > MAX_ODOH_PACKET_SIZE {
            return Err(P2pError::OdohMessageTooLarge);
        }
        if raw[0] != ODOH_VERSION {
            return Err(P2pError::InvalidOdohPacket("unsupported version"));
        }
        if u16::from_le_bytes([raw[2], raw[3]]) != 0 {
            return Err(P2pError::InvalidOdohPacket("reserved flags are nonzero"));
        }
        let mut request_id = [0u8; REQUEST_ID_SIZE];
        request_id.copy_from_slice(&raw[4..12]);
        Self::new(
            OdohOpcode::try_from(raw[1])?,
            request_id,
            raw[12..].to_vec(),
        )
    }

    fn validate(&self) -> Result<(), P2pError> {
        if self.request_id == [0u8; REQUEST_ID_SIZE] {
            return Err(P2pError::InvalidOdohPacket("request identifier is zero"));
        }
        if 12usize.saturating_add(self.body.len()) > MAX_ODOH_PACKET_SIZE {
            return Err(P2pError::OdohMessageTooLarge);
        }
        Ok(())
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DirectTargetLocator {
    pub target_peer_key: [u8; 33],
    pub address: SocketAddr,
}

impl DirectTargetLocator {
    pub fn new(
        target_peer_key: [u8; 33],
        address: SocketAddr,
        network: &Network,
    ) -> Result<Self, OdohClientError> {
        VerifyingKey::from_sec1_bytes(&target_peer_key)
            .map_err(|_| OdohClientError::InvalidTargetConfig("invalid target peer key"))?;
        if address.port() == 0 || address.ip().is_unspecified() {
            return Err(OdohClientError::InvalidTargetConfig(
                "invalid target endpoint",
            ));
        }
        if network.name != "regtest" && !is_publicly_routable(address.ip()) {
            return Err(OdohClientError::InvalidTargetConfig(
                "target endpoint is not publicly routable",
            ));
        }
        Ok(Self {
            target_peer_key,
            address,
        })
    }

    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(55);
        out.push(DIRECT_BRONTIDE);
        out.extend(self.target_peer_key);
        out.extend(19u16.to_le_bytes());
        match self.address.ip() {
            IpAddr::V4(address) => {
                out.push(4);
                out.extend([0u8; 10]);
                out.extend([0xff, 0xff]);
                out.extend(address.octets());
            }
            IpAddr::V6(address) => {
                out.push(6);
                out.extend(address.octets());
            }
        }
        out.extend(self.address.port().to_le_bytes());
        out
    }

    fn decode(reader: &mut WireReader<'_>, network: &Network) -> Result<Self, OdohClientError> {
        if reader.u8()? != DIRECT_BRONTIDE {
            return Err(OdohClientError::InvalidTargetConfig(
                "unsupported target locator",
            ));
        }
        let target_peer_key = reader.array::<33>()?;
        if reader.u16_le()? != 19 {
            return Err(OdohClientError::InvalidTargetConfig(
                "unsupported target locator",
            ));
        }
        let host_type = reader.u8()?;
        let host = reader.array::<16>()?;
        let port = reader.u16_le()?;
        let address = match host_type {
            4 if host[..10] == [0u8; 10] && host[10..12] == [0xff, 0xff] => SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(host[12], host[13], host[14], host[15])),
                port,
            ),
            6 => SocketAddr::new(IpAddr::V6(Ipv6Addr::from(host)), port),
            _ => {
                return Err(OdohClientError::InvalidTargetConfig(
                    "target host encoding mismatch",
                ));
            }
        };
        Self::new(target_peer_key, address, network)
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OdohConfig {
    pub public_key: [u8; 32],
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct TargetConfigRecord {
    pub locator: DirectTargetLocator,
    pub sequence: u64,
    pub issued_at: u64,
    pub expires_at: u64,
    pub configurations: Vec<OdohConfig>,
    pub record_id: [u8; 32],
    raw: Vec<u8>,
}

impl TargetConfigRecord {
    pub fn decode_and_verify(
        raw: &[u8],
        expected_locator: &DirectTargetLocator,
        network: &Network,
        now: u64,
    ) -> Result<Self, OdohClientError> {
        if raw.is_empty() || raw.len() > MAX_CONFIG_SIZE {
            return Err(OdohClientError::InvalidTargetConfig(
                "target record exceeds size limit",
            ));
        }
        let mut reader = WireReader::new(raw);
        if reader.u8()? != 1 {
            return Err(OdohClientError::InvalidTargetConfig(
                "unsupported target record version",
            ));
        }
        if reader.u32_le()? != network.magic {
            return Err(OdohClientError::InvalidTargetConfig(
                "wrong target record network",
            ));
        }
        let locator = DirectTargetLocator::decode(&mut reader, network)?;
        if locator != *expected_locator {
            return Err(OdohClientError::InvalidTargetConfig(
                "target locator substitution",
            ));
        }
        let sequence = reader.u64_le()?;
        let issued_at = reader.u64_le()?;
        let expires_at = reader.u64_le()?;
        let configs_length = usize::from(reader.u16_le()?);
        let configs = reader.take(configs_length)?.to_vec();
        let unsigned_length = reader.position();
        let signature_length = usize::from(reader.u8()?);
        if !(8..=72).contains(&signature_length) || reader.remaining() != signature_length {
            return Err(OdohClientError::InvalidTargetConfig(
                "invalid target signature length",
            ));
        }
        let signature_bytes = reader.take(signature_length)?;
        if reader.remaining() != 0 || sequence == 0 {
            return Err(OdohClientError::InvalidTargetConfig(
                "invalid target record sequence or trailing bytes",
            ));
        }
        if issued_at > now.saturating_add(300)
            || expires_at <= issued_at
            || expires_at <= now
            || expires_at.saturating_sub(issued_at) > MAX_CONFIG_LIFETIME
        {
            return Err(OdohClientError::InvalidTargetConfig(
                "invalid target record lifetime",
            ));
        }
        let configurations = decode_configs(&configs)?;
        let signature = Signature::from_der(signature_bytes).map_err(|_| {
            OdohClientError::InvalidTargetConfig("target signature is not strict DER")
        })?;
        if signature != signature.normalize_s() {
            return Err(OdohClientError::InvalidTargetConfig(
                "target signature is high-S",
            ));
        }
        let digest = blake2b_256(&[TARGET_CONFIG_TAG, &raw[..unsigned_length]]);
        let key = VerifyingKey::from_sec1_bytes(&locator.target_peer_key)
            .map_err(|_| OdohClientError::InvalidTargetConfig("invalid target peer key"))?;
        key.verify_prehash(digest.as_bytes(), &signature)
            .map_err(|_| OdohClientError::InvalidTargetConfig("invalid target signature"))?;
        let record_id = blake2b_256(&[raw]).into_bytes();
        Ok(Self {
            locator,
            sequence,
            issued_at,
            expires_at,
            configurations,
            record_id,
            raw: raw.to_vec(),
        })
    }

    pub fn raw(&self) -> &[u8] {
        &self.raw
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct OdohExchange {
    pub response: Vec<u8>,
    pub proxy: SocketAddr,
    pub retries: usize,
    pub target_config_id: [u8; 32],
    pub target_sequence: u64,
}

#[derive(Debug, Error)]
pub enum OdohClientError {
    #[error("invalid ODoH target configuration: {0}")]
    InvalidTargetConfig(&'static str),
    #[error("invalid ODoH message: {0}")]
    InvalidMessage(&'static str),
    #[error("ODoH cryptographic operation failed")]
    Cryptography,
    #[error("no live peer advertised the experimental ODoH proxy service")]
    NoCapableProxy,
    #[error("request-id randomness is unavailable")]
    RandomnessUnavailable,
    #[error("ODoH peer transport failed: {0}")]
    Transport(#[from] P2pError),
    #[error("ODoH peer returned status {0}")]
    Status(u8),
    #[error("ODoH peer sent an unsolicited or unexpected packet")]
    UnexpectedPacket,
}

pub struct OdohClient<C>
where
    C: DnsRelayPeerConnector,
{
    network: Network,
    proxies: PeerManager,
    connector: C,
    target: DirectTargetLocator,
    timeout: Duration,
    alternate_retries: usize,
}

impl<C> OdohClient<C>
where
    C: DnsRelayPeerConnector,
{
    pub fn with_connector(
        network: Network,
        proxies: PeerManager,
        target: DirectTargetLocator,
        connector: C,
    ) -> Self {
        Self {
            network,
            proxies,
            connector,
            target,
            timeout: DEFAULT_ODOH_TIMEOUT,
            alternate_retries: 1,
        }
    }

    pub fn resolve(&mut self, query: &[u8]) -> Result<OdohExchange, OdohClientError> {
        let query = prepare_dns_relay_query(query)
            .map_err(|_| OdohClientError::InvalidMessage("invalid base-relay DNS query"))?;
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let candidates = self
            .proxies
            .select_outbound(self.proxies.len(), now)
            .into_iter()
            .take(self.alternate_retries.saturating_add(1))
            .collect::<Vec<_>>();
        let mut last_error = None;

        for (retry, proxy) in candidates.into_iter().enumerate() {
            match self.exchange_with_proxy(proxy, &query, now) {
                Ok((response, record)) => {
                    self.proxies.record_transport_success(proxy, now);
                    return Ok(OdohExchange {
                        response,
                        proxy,
                        retries: retry,
                        target_config_id: record.record_id,
                        target_sequence: record.sequence,
                    });
                }
                Err(error) => {
                    self.proxies.record_transient_failure(proxy);
                    last_error = Some(error);
                }
            }
        }

        Err(last_error.unwrap_or(OdohClientError::NoCapableProxy))
    }

    fn exchange_with_proxy(
        &mut self,
        proxy: SocketAddr,
        query: &[u8],
        now: u64,
    ) -> Result<(Vec<u8>, TargetConfigRecord), OdohClientError> {
        let deadline = Instant::now()
            .checked_add(self.timeout)
            .unwrap_or_else(Instant::now);
        let mut connection = self.connector.connect(proxy, &self.network, self.timeout)?;
        let result = (|| {
            let remote = connection.handshake(requester_version(), deadline)?;
            if remote.services & EXPERIMENTAL_ODOH_SERVICE == 0 {
                return Err(OdohClientError::NoCapableProxy);
            }

            let caps_id = request_id()?;
            send_odoh(
                &mut connection,
                OdohOpcode::GetCaps,
                caps_id,
                Vec::new(),
                deadline,
            )?;
            let caps = receive_odoh(&mut connection, caps_id, OdohOpcode::Caps, deadline)?;
            validate_caps(&caps.body)?;

            let config_id = request_id()?;
            let mut get_config = self.target.encode();
            get_config.push(1);
            send_odoh(
                &mut connection,
                OdohOpcode::GetConfig,
                config_id,
                get_config,
                deadline,
            )?;
            let config = receive_odoh(&mut connection, config_id, OdohOpcode::Config, deadline)?;
            let record_raw = decode_config_body(&config.body)?;
            let record = TargetConfigRecord::decode_and_verify(
                record_raw,
                &self.target,
                &self.network,
                now,
            )?;
            let selected =
                record
                    .configurations
                    .first()
                    .ok_or(OdohClientError::InvalidTargetConfig(
                        "no supported ODoH configuration",
                    ))?;
            let encrypted = encrypt_query(selected, query)?;
            let query_id = request_id()?;
            let body = encode_client_query(&self.target, record.record_id, &encrypted.message)?;
            send_odoh(
                &mut connection,
                OdohOpcode::ClientQuery,
                query_id,
                body,
                deadline,
            )?;
            let response = receive_odoh(
                &mut connection,
                query_id,
                OdohOpcode::ClientResponse,
                deadline,
            )?;
            let response_message = decode_response_body(&response.body)?;
            let decrypted = decrypt_response(encrypted, response_message)?;
            validate_dns_relay_response(query, &decrypted)
                .map_err(|_| OdohClientError::InvalidMessage("DNS response correlation failed"))?;
            Ok((decrypted, record))
        })();
        connection.close();
        result
    }
}

impl OdohClient<crate::TcpDnsRelayPeerConnector> {
    pub fn new(network: Network, proxies: PeerManager, target: DirectTargetLocator) -> Self {
        Self::with_connector(network, proxies, target, crate::TcpDnsRelayPeerConnector)
    }
}

struct EncryptedQuery {
    message: Vec<u8>,
    context: OdohSenderContext,
    plaintext: Vec<u8>,
}

fn encrypt_query(config: &OdohConfig, dns: &[u8]) -> Result<EncryptedQuery, OdohClientError> {
    let contents = encode_config_contents(config);
    let key_id = derive_key_id(&contents)?;
    let public_key = <X25519HkdfSha256 as KemTrait>::PublicKey::from_bytes(&config.public_key)
        .map_err(|_| OdohClientError::InvalidTargetConfig("invalid HPKE public key"))?;
    let (encapped, mut context) = hpke::setup_sender::<AesGcm128, HkdfSha256, X25519HkdfSha256>(
        &OpModeS::Base,
        &public_key,
        QUERY_INFO,
    )
    .map_err(|_| OdohClientError::Cryptography)?;
    let plaintext = encode_plaintext(dns, 128)?;
    let aad = message_aad(1, &key_id);
    let ciphertext = context
        .seal(&plaintext, &aad)
        .map_err(|_| OdohClientError::Cryptography)?;
    let mut encrypted = encapped.to_bytes().to_vec();
    encrypted.extend(ciphertext);
    Ok(EncryptedQuery {
        message: encode_odoh_message(1, &key_id, &encrypted)?,
        context,
        plaintext,
    })
}

fn decrypt_response(query: EncryptedQuery, message: &[u8]) -> Result<Vec<u8>, OdohClientError> {
    let (message_type, response_nonce, ciphertext) = decode_odoh_message(message)?;
    if message_type != 2 || response_nonce.len() != 16 {
        return Err(OdohClientError::InvalidMessage(
            "invalid ODoH response nonce or type",
        ));
    }
    let mut secret = [0u8; 16];
    query
        .context
        .export(RESPONSE_INFO, &mut secret)
        .map_err(|_| OdohClientError::Cryptography)?;
    let (key, nonce) = derive_response_secrets(&secret, &query.plaintext, response_nonce)?;
    let cipher =
        ResponseAes128Gcm::new_from_slice(&key).map_err(|_| OdohClientError::Cryptography)?;
    let aad = message_aad(2, response_nonce);
    let plaintext = cipher
        .decrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: ciphertext,
                aad: &aad,
            },
        )
        .map_err(|_| OdohClientError::Cryptography)?;
    decode_plaintext(&plaintext)
}

fn derive_response_secrets(
    secret: &[u8; 16],
    query_plaintext: &[u8],
    response_nonce: &[u8],
) -> Result<([u8; 16], [u8; 12]), OdohClientError> {
    let nonce_length = u16::try_from(response_nonce.len())
        .map_err(|_| OdohClientError::InvalidMessage("response nonce is too long"))?;
    let mut salt = Vec::with_capacity(query_plaintext.len() + 2 + response_nonce.len());
    salt.extend(query_plaintext);
    salt.extend(nonce_length.to_be_bytes());
    salt.extend(response_nonce);
    let hkdf = Hkdf::<Sha256>::new(Some(&salt), secret);
    let mut key = [0u8; 16];
    let mut nonce = [0u8; 12];
    hkdf.expand(RESPONSE_KEY_INFO, &mut key)
        .map_err(|_| OdohClientError::Cryptography)?;
    hkdf.expand(RESPONSE_NONCE_INFO, &mut nonce)
        .map_err(|_| OdohClientError::Cryptography)?;
    Ok((key, nonce))
}

fn encode_config_contents(config: &OdohConfig) -> Vec<u8> {
    let mut out = Vec::with_capacity(40);
    out.extend(KEM_X25519_SHA256.to_be_bytes());
    out.extend(KDF_HKDF_SHA256.to_be_bytes());
    out.extend(AEAD_AES_128_GCM.to_be_bytes());
    out.extend(32u16.to_be_bytes());
    out.extend(config.public_key);
    out
}

fn derive_key_id(contents: &[u8]) -> Result<[u8; 32], OdohClientError> {
    let hkdf = Hkdf::<Sha256>::new(Some(&[]), contents);
    let mut key_id = [0u8; 32];
    hkdf.expand(KEY_ID_INFO, &mut key_id)
        .map_err(|_| OdohClientError::Cryptography)?;
    Ok(key_id)
}

fn decode_configs(raw: &[u8]) -> Result<Vec<OdohConfig>, OdohClientError> {
    let mut outer = TlsReader::new(raw);
    let list = outer.vector(false)?;
    if outer.remaining() != 0 {
        return Err(OdohClientError::InvalidTargetConfig(
            "trailing configuration bytes",
        ));
    }
    let mut reader = TlsReader::new(list);
    let mut supported = Vec::new();
    while reader.remaining() != 0 {
        let version = reader.u16_be()?;
        let contents = reader.vector(false)?;
        if version != SUPPORTED_CONFIG_VERSION {
            continue;
        }
        let mut contents_reader = TlsReader::new(contents);
        if contents_reader.u16_be()? != KEM_X25519_SHA256
            || contents_reader.u16_be()? != KDF_HKDF_SHA256
            || contents_reader.u16_be()? != AEAD_AES_128_GCM
        {
            return Err(OdohClientError::InvalidTargetConfig(
                "unsupported ODoH cipher suite",
            ));
        }
        let public_key_bytes = contents_reader.vector(false)?;
        if public_key_bytes.len() != 32 || contents_reader.remaining() != 0 {
            return Err(OdohClientError::InvalidTargetConfig(
                "invalid ODoH public key",
            ));
        }
        let mut public_key = [0u8; 32];
        public_key.copy_from_slice(public_key_bytes);
        supported.push(OdohConfig { public_key });
    }
    if supported.is_empty() {
        return Err(OdohClientError::InvalidTargetConfig(
            "no supported ODoH configuration",
        ));
    }
    Ok(supported)
}

fn encode_plaintext(dns: &[u8], block_size: usize) -> Result<Vec<u8>, OdohClientError> {
    if dns.is_empty() || dns.len() > u16::MAX as usize || block_size == 0 {
        return Err(OdohClientError::InvalidMessage(
            "invalid DNS plaintext size",
        ));
    }
    let padding_size = (block_size - dns.len() % block_size) % block_size;
    let mut out = Vec::with_capacity(4 + dns.len() + padding_size);
    out.extend((dns.len() as u16).to_be_bytes());
    out.extend(dns);
    out.extend((padding_size as u16).to_be_bytes());
    out.resize(out.len() + padding_size, 0);
    Ok(out)
}

fn decode_plaintext(raw: &[u8]) -> Result<Vec<u8>, OdohClientError> {
    let mut reader = TlsReader::new(raw);
    let dns = reader.vector(false)?.to_vec();
    let padding = reader.vector(true)?;
    if reader.remaining() != 0 || padding.iter().any(|byte| *byte != 0) {
        return Err(OdohClientError::InvalidMessage(
            "invalid ODoH plaintext padding",
        ));
    }
    Ok(dns)
}

fn encode_odoh_message(
    message_type: u8,
    key_id: &[u8],
    encrypted: &[u8],
) -> Result<Vec<u8>, OdohClientError> {
    let mut out = Vec::new();
    out.push(message_type);
    write_tls_vector(&mut out, key_id)?;
    write_tls_vector(&mut out, encrypted)?;
    Ok(out)
}

fn decode_odoh_message(raw: &[u8]) -> Result<(u8, &[u8], &[u8]), OdohClientError> {
    if raw.is_empty() {
        return Err(OdohClientError::InvalidMessage("truncated ODoH message"));
    }
    let mut reader = TlsReader::new(&raw[1..]);
    let key_id = reader.vector(true)?;
    let encrypted = reader.vector(false)?;
    if reader.remaining() != 0 {
        return Err(OdohClientError::InvalidMessage(
            "trailing ODoH message bytes",
        ));
    }
    Ok((raw[0], key_id, encrypted))
}

fn message_aad(message_type: u8, key_id: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(3 + key_id.len());
    out.push(message_type);
    out.extend((key_id.len() as u16).to_be_bytes());
    out.extend(key_id);
    out
}

fn encode_client_query(
    locator: &DirectTargetLocator,
    config_id: [u8; 32],
    message: &[u8],
) -> Result<Vec<u8>, OdohClientError> {
    if message.is_empty() || message.len() > MAX_ODOH_QUERY_SIZE {
        return Err(OdohClientError::InvalidMessage("ODoH query is too large"));
    }
    let mut out = locator.encode();
    out.extend(config_id);
    out.extend((message.len() as u16).to_le_bytes());
    out.extend(message);
    out.extend(0u16.to_le_bytes());
    Ok(out)
}

fn decode_config_body(raw: &[u8]) -> Result<&[u8], OdohClientError> {
    if raw.len() < 2 {
        return Err(OdohClientError::InvalidMessage("truncated CONFIG body"));
    }
    let length = usize::from(u16::from_le_bytes([raw[0], raw[1]]));
    if length == 0 || length > MAX_CONFIG_SIZE || raw.len() != 2 + length {
        return Err(OdohClientError::InvalidMessage("invalid CONFIG length"));
    }
    Ok(&raw[2..])
}

fn decode_response_body(raw: &[u8]) -> Result<&[u8], OdohClientError> {
    if raw.len() < 6 {
        return Err(OdohClientError::InvalidMessage("truncated response body"));
    }
    let length = u32::from_le_bytes([raw[0], raw[1], raw[2], raw[3]]) as usize;
    if length == 0 || length > MAX_ODOH_RESPONSE_SIZE || raw.len() < 4 + length + 2 {
        return Err(OdohClientError::InvalidMessage("invalid response length"));
    }
    let padding_offset = 4 + length;
    let padding_length = usize::from(u16::from_le_bytes([
        raw[padding_offset],
        raw[padding_offset + 1],
    ]));
    if padding_length > 4096
        || raw.len() != padding_offset + 2 + padding_length
        || raw[padding_offset + 2..].iter().any(|byte| *byte != 0)
    {
        return Err(OdohClientError::InvalidMessage("invalid response padding"));
    }
    Ok(&raw[4..padding_offset])
}

fn validate_caps(raw: &[u8]) -> Result<(), OdohClientError> {
    if raw.len() != 15 || raw[0] & ROLE_PROXY == 0 {
        return Err(OdohClientError::InvalidMessage(
            "peer omitted valid proxy capability",
        ));
    }
    let max_query = u16::from_le_bytes([raw[1], raw[2]]) as usize;
    let max_response = u32::from_le_bytes([raw[3], raw[4], raw[5], raw[6]]) as usize;
    let max_live = u16::from_le_bytes([raw[7], raw[8]]);
    let max_config = u16::from_le_bytes([raw[9], raw[10]]) as usize;
    if !(256..=MAX_ODOH_QUERY_SIZE).contains(&max_query)
        || !(512..=MAX_ODOH_RESPONSE_SIZE).contains(&max_response)
        || max_live == 0
        || !(128..=MAX_CONFIG_SIZE).contains(&max_config)
    {
        return Err(OdohClientError::InvalidMessage(
            "invalid proxy capability limits",
        ));
    }
    Ok(())
}

fn send_odoh<C: DnsRelayPeerConnection>(
    connection: &mut C,
    opcode: OdohOpcode,
    request_id: [u8; 8],
    body: Vec<u8>,
    deadline: Instant,
) -> Result<(), OdohClientError> {
    let packet = OdnsPacket::new(opcode, request_id, body)?;
    connection.send_packet(&Packet::Odoh(packet), deadline)?;
    Ok(())
}

fn receive_odoh<C: DnsRelayPeerConnection>(
    connection: &mut C,
    request_id: [u8; 8],
    expected: OdohOpcode,
    deadline: Instant,
) -> Result<OdnsPacket, OdohClientError> {
    for _ in 0..MAX_ADVISORY_PACKETS {
        match connection.receive_packet(deadline)? {
            Packet::Odoh(packet) if packet.request_id == request_id => {
                if packet.opcode == OdohOpcode::Error {
                    if packet.body.len() != 7 || packet.body[0] > 12 {
                        return Err(OdohClientError::InvalidMessage("malformed ODoH error"));
                    }
                    return Err(OdohClientError::Status(packet.body[0]));
                }
                if packet.opcode != expected {
                    return Err(OdohClientError::UnexpectedPacket);
                }
                return Ok(packet);
            }
            Packet::Odoh(_) => return Err(OdohClientError::UnexpectedPacket),
            Packet::Ping(nonce) => connection.send_packet(&Packet::Pong(nonce), deadline)?,
            Packet::Version(_) => return Err(OdohClientError::UnexpectedPacket),
            _ => {}
        }
    }
    Err(OdohClientError::UnexpectedPacket)
}

fn requester_version() -> VersionPacket {
    VersionPacket {
        services: 0,
        remote: crate::NetAddress {
            services: 0,
            ..crate::NetAddress::default()
        },
        ..VersionPacket::default()
    }
}

fn request_id() -> Result<[u8; 8], OdohClientError> {
    for _ in 0..16 {
        let mut id = [0u8; 8];
        getrandom::fill(&mut id).map_err(|_| OdohClientError::RandomnessUnavailable)?;
        if id != [0u8; 8] {
            return Ok(id);
        }
    }
    Err(OdohClientError::RandomnessUnavailable)
}

fn write_tls_vector(out: &mut Vec<u8>, value: &[u8]) -> Result<(), OdohClientError> {
    let length = u16::try_from(value.len())
        .map_err(|_| OdohClientError::InvalidMessage("TLS vector is too long"))?;
    out.extend(length.to_be_bytes());
    out.extend(value);
    Ok(())
}

struct WireReader<'a> {
    raw: &'a [u8],
    position: usize,
}

impl<'a> WireReader<'a> {
    fn new(raw: &'a [u8]) -> Self {
        Self { raw, position: 0 }
    }

    fn position(&self) -> usize {
        self.position
    }

    fn remaining(&self) -> usize {
        self.raw.len().saturating_sub(self.position)
    }

    fn take(&mut self, length: usize) -> Result<&'a [u8], OdohClientError> {
        let end = self
            .position
            .checked_add(length)
            .filter(|end| *end <= self.raw.len())
            .ok_or(OdohClientError::InvalidTargetConfig(
                "truncated target record",
            ))?;
        let value = &self.raw[self.position..end];
        self.position = end;
        Ok(value)
    }

    fn array<const N: usize>(&mut self) -> Result<[u8; N], OdohClientError> {
        let mut value = [0u8; N];
        value.copy_from_slice(self.take(N)?);
        Ok(value)
    }

    fn u8(&mut self) -> Result<u8, OdohClientError> {
        Ok(self.take(1)?[0])
    }

    fn u16_le(&mut self) -> Result<u16, OdohClientError> {
        Ok(u16::from_le_bytes(self.array()?))
    }

    fn u32_le(&mut self) -> Result<u32, OdohClientError> {
        Ok(u32::from_le_bytes(self.array()?))
    }

    fn u64_le(&mut self) -> Result<u64, OdohClientError> {
        Ok(u64::from_le_bytes(self.array()?))
    }
}

struct TlsReader<'a>(WireReader<'a>);

impl<'a> TlsReader<'a> {
    fn new(raw: &'a [u8]) -> Self {
        Self(WireReader::new(raw))
    }

    fn remaining(&self) -> usize {
        self.0.remaining()
    }

    fn u16_be(&mut self) -> Result<u16, OdohClientError> {
        Ok(u16::from_be_bytes(self.0.array()?))
    }

    fn vector(&mut self, allow_empty: bool) -> Result<&'a [u8], OdohClientError> {
        let length = usize::from(self.u16_be()?);
        if !allow_empty && length == 0 {
            return Err(OdohClientError::InvalidMessage("empty TLS vector"));
        }
        self.0
            .take(length)
            .map_err(|_| OdohClientError::InvalidMessage("truncated TLS vector"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn odns_envelope_is_strict_and_round_trips() {
        let packet = OdnsPacket::new(OdohOpcode::GetCaps, [1u8; 8], vec![2, 3]).unwrap();
        assert_eq!(
            OdnsPacket::decode(&packet.encode().unwrap()).unwrap(),
            packet
        );
        let mut flags = packet.encode().unwrap();
        flags[2] = 1;
        assert!(OdnsPacket::decode(&flags).is_err());
        assert!(OdnsPacket::new(OdohOpcode::GetCaps, [0u8; 8], Vec::new()).is_err());
    }

    #[test]
    fn deterministic_profile_primitives_match_hsd() {
        let config = OdohConfig {
            public_key: hex::decode(
                "8f40c5adb68f25624ae5b214ea767a6e0ddee42f4a9cfb73d04fc85b3c9b4f5d",
            )
            .unwrap()
            .try_into()
            .unwrap(),
        };
        let contents = encode_config_contents(&config);
        assert_eq!(
            hex::encode(&contents),
            "00200001000100208f40c5adb68f25624ae5b214ea767a6e0ddee42f4a9cfb73d04fc85b3c9b4f5d"
        );
        assert_eq!(
            hex::encode(derive_key_id(&contents).unwrap()),
            "91eaf90f9a4fa36870e3799a652817f85f888e9652f39a11faaba70470d8f753"
        );
        let dns = hex::decode(
            "123401100001000000000001037777770972656c617974657374000001000100002904d0000080000000",
        )
        .unwrap();
        let plaintext = encode_plaintext(&dns, 128).unwrap();
        assert_eq!(
            hex::encode(&plaintext),
            "002a123401100001000000000001037777770972656c617974657374000001000100002904d000008000000000560000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        );
        let (key, nonce) = derive_response_secrets(
            &hex::decode("000102030405060708090a0b0c0d0e0f")
                .unwrap()
                .try_into()
                .unwrap(),
            &plaintext,
            &hex::decode("101112131415161718191a1b1c1d1e1f").unwrap(),
        )
        .unwrap();
        assert_eq!(hex::encode(key), "56193fc769e00a71a627887fd954dab9");
        assert_eq!(hex::encode(nonce), "6dcdcf8e2b4ec24f49ac986f");
    }

    #[test]
    fn signed_target_record_vector_matches_hsd() {
        let network = hns_core::network::NetworkKind::Regtest.network();
        let locator = DirectTargetLocator::new(
            hex::decode("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
                .unwrap()
                .try_into()
                .unwrap(),
            "127.0.0.1:14039".parse().unwrap(),
            &network,
        )
        .unwrap();
        assert_eq!(
            hex::encode(locator.encode()),
            "010279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f8179813000400000000000000000000ffff7f000001d736"
        );
        let raw = hex::decode(concat!(
            "01cf9538ae010279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            "13000400000000000000000000ffff7f000001d736070000000000000000f153650000000080425565",
            "000000002e00002c0001002800200001000100208f40c5adb68f25624ae5b214ea767a6e0ddee42f",
            "4a9cfb73d04fc85b3c9b4f5d473045022100ee73e264dd74d0c05ba717ba46c10634d2f262053286",
            "84596fabae02ee9caca2022055650d588c8c19ddab2540145f1aba212816bd5a0acf4f3802af5992",
            "f04fe2e0"
        ))
        .unwrap();
        let record =
            TargetConfigRecord::decode_and_verify(&raw, &locator, &network, 1_700_000_001).unwrap();
        assert_eq!(record.sequence, 7);
        assert_eq!(record.configurations.len(), 1);
        assert_eq!(
            hex::encode(record.record_id),
            "ba9524fe3591e6165d9646a6c08975f6bc38448d1a60bf7f1810534e465e612c"
        );
    }
}
