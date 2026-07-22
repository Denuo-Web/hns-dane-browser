use crate::{HeaderSyncSession, P2pError, Packet, PeerConnection, VersionPacket};
use hns_core::bytes::{ParseError, Reader};
use hns_core::hash::blake2b_256;
use hns_core::network::{Network, NetworkKind};
use k256::ecdsa::signature::hazmat::PrehashVerifier;
use k256::ecdsa::{Signature, VerifyingKey};
use std::net::SocketAddr;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use thiserror::Error;

pub const EXPERIMENTAL_HNSR_PACKET: u8 = 0xf3;
pub const EXPERIMENTAL_HNSR_RENDEZVOUS_SERVICE: u64 = 0x0400_0000;
pub const EXPERIMENTAL_HNSR_RELAY_SERVICE: u64 = 0x0800_0000;

const HNSR_VERSION: u8 = 1;
const HNS_NODE_V1: u16 = 1;
const MAX_HNSR_BODY_BYTES: usize = 65_523;
const MAX_HNSR_RECORD_BYTES: usize = 8_192;
const MAX_HNSR_SIGNATURE_BYTES: usize = 80;
const MAX_HNSR_RECORDS: usize = 16;
const MAX_HNSR_CONTACTS: usize = 16;
const MAX_HNSR_RESPONSE_PACKETS: usize = 64;
const MAX_TICKET_LIFETIME_SECONDS: u64 = 7_200;
const MAX_ROUTE_LIFETIME_SECONDS: u64 = 7_200;
const MAX_DELEGATION_LIFETIME_SECONDS: u64 = 604_800;

const DOMAIN_DELEGATION: &[u8] = b"HNSR-ENDPOINT-DELEGATION-V1\0";
const DOMAIN_PEER_ROUTE: &[u8] = b"HNSR-PEER-ROUTE-V1\0";
const DOMAIN_RENDEZVOUS_NODE: &[u8] = b"HNSR-RENDEZVOUS-NODE-V1\0";
const DOMAIN_ROUTE: &[u8] = b"HNSR-ROUTE-RECORD-V1\0";
const DOMAIN_TICKET_ENDPOINT: &[u8] = b"HNSR-RELAY-CONFIRM-V1\0";
const DOMAIN_TICKET_RELAY: &[u8] = b"HNSR-RELAY-TICKET-V1\0";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
enum HnsrOpcode {
    FindNode = 0,
    Nodes = 1,
    GetRoute = 4,
    Routes = 5,
    SampleRoutes = 6,
    Error = 20,
}

impl HnsrOpcode {
    fn name(self) -> &'static str {
        match self {
            Self::FindNode => "FINDNODE",
            Self::Nodes => "NODES",
            Self::GetRoute => "GETROUTE",
            Self::Routes => "ROUTES",
            Self::SampleRoutes => "SAMPLEROUTES",
            Self::Error => "ERROR",
        }
    }
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct HnsrEnvelope {
    opcode: u8,
    context: [u8; 8],
    body: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct RendezvousContact {
    node_id: [u8; 32],
    services: u64,
    peer_key: [u8; 33],
    observed_at: u64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct EndpointDelegation {
    authorization_id: [u8; 32],
    endpoint_key: [u8; 33],
    sequence: u64,
    issued_at: u64,
    expires_at: u64,
    max_active_circuits: u16,
    max_bytes_per_circuit: u64,
    flags: u16,
    unsigned: Vec<u8>,
    signature: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct RelayTicket {
    network_magic: u32,
    profile: u16,
    transport: u8,
    host_type: u8,
    port: u16,
    relay_key: [u8; 33],
    endpoint_key: [u8; 33],
    reservation_id: [u8; 16],
    issued_at: u64,
    expires_at: u64,
    max_active_circuits: u16,
    max_bytes_per_circuit: u64,
    max_total_bytes: u64,
    flags: u16,
    unsigned: Vec<u8>,
    relay_signature: Vec<u8>,
    endpoint_signature: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct RouteRecord {
    route_key: [u8; 32],
    profile: u16,
    sequence: u64,
    issued_at: u64,
    expires_at: u64,
    delegation: EndpointDelegation,
    tickets: Vec<RelayTicket>,
    unsigned: Vec<u8>,
    signature: Vec<u8>,
    raw: Vec<u8>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct HnsrProbeReport {
    network: &'static str,
    bootstrap: String,
    remote_services: u64,
    remote_height: u32,
    contacts: usize,
    sampled_records: usize,
    exact_records: usize,
    route_key: [u8; 32],
    route_sequence: u64,
    endpoint_key: [u8; 33],
    relay_keys: Vec<[u8; 33]>,
    observed_opcodes: Vec<&'static str>,
    elapsed_millis: u128,
}

#[derive(Debug, Error, Eq, PartialEq)]
pub enum HnsrProbeError {
    #[error("the private HNSR diagnostic is regtest-only")]
    NetworkDisabled,
    #[error("invalid HNSR bootstrap endpoint")]
    InvalidBootstrap,
    #[error("HNSR randomness is unavailable")]
    RandomnessUnavailable,
    #[error("HNSR peer lacks the rendezvous service bit")]
    MissingRendezvousService,
    #[error("invalid HNSR envelope: {0}")]
    Envelope(&'static str),
    #[error("invalid HNSR response: {0}")]
    Response(&'static str),
    #[error("HNSR peer error {code}: {detail}")]
    PeerError { code: u16, detail: String },
    #[error("invalid HNSR route: {0}")]
    Route(&'static str),
    #[error("HNSR cryptographic verification failed: {0}")]
    Cryptography(&'static str),
    #[error("HNSR route was not returned by exact lookup")]
    ExactRouteMissing,
    #[error("HNSR parsing failed: {0}")]
    Parse(#[from] ParseError),
    #[error("Handshake peer operation failed: {0}")]
    P2p(#[from] P2pError),
}

impl HnsrEnvelope {
    fn encode(&self) -> Result<Vec<u8>, HnsrProbeError> {
        if self.context.iter().all(|byte| *byte == 0) {
            return Err(HnsrProbeError::Envelope("zero context identifier"));
        }
        if self.body.len() > MAX_HNSR_BODY_BYTES {
            return Err(HnsrProbeError::Envelope("body exceeds packet limit"));
        }
        let mut encoded = Vec::with_capacity(12usize.saturating_add(self.body.len()));
        encoded.push(HNSR_VERSION);
        encoded.push(self.opcode);
        encoded.extend(0u16.to_le_bytes());
        encoded.extend(self.context);
        encoded.extend(&self.body);
        Ok(encoded)
    }

    fn decode(encoded: &[u8]) -> Result<Self, HnsrProbeError> {
        if encoded.len() < 12 || encoded.len() > 12 + MAX_HNSR_BODY_BYTES {
            return Err(HnsrProbeError::Envelope("invalid encoded length"));
        }
        let mut reader = Reader::new(encoded);
        let version = reader.read_u8()?;
        let opcode = reader.read_u8()?;
        let flags = read_u16_le(&mut reader)?;
        let context = reader.read_array()?;
        let body = reader.read_bytes(reader.remaining())?.to_vec();
        if version != HNSR_VERSION {
            return Err(HnsrProbeError::Envelope("unsupported version"));
        }
        if flags != 0 {
            return Err(HnsrProbeError::Envelope("reserved flags are nonzero"));
        }
        if context.iter().all(|byte| *byte == 0) {
            return Err(HnsrProbeError::Envelope("zero context identifier"));
        }
        Ok(Self {
            opcode,
            context,
            body,
        })
    }
}

impl RendezvousContact {
    fn parse(reader: &mut Reader<'_>, network: &Network, now: u64) -> Result<Self, HnsrProbeError> {
        let node_id = reader.read_array()?;
        let host_type = reader.read_u8()?;
        let _host = reader.read_array::<16>()?;
        let port = read_u16_le(reader)?;
        let services = reader.read_u64_le()?;
        let peer_key = reader.read_array()?;
        let observed_at = reader.read_u64_le()?;
        if !matches!(host_type, 1 | 2)
            || port == 0
            || services & EXPERIMENTAL_HNSR_RENDEZVOUS_SERVICE == 0
            || observed_at > now.saturating_add(600)
            || now.saturating_sub(observed_at) > 86_400
        {
            return Err(HnsrProbeError::Response("invalid rendezvous contact"));
        }
        VerifyingKey::from_sec1_bytes(&peer_key)
            .map_err(|_| HnsrProbeError::Cryptography("invalid rendezvous public key"))?;
        let magic = network.magic.to_le_bytes();
        let expected = blake2b_256(&[DOMAIN_RENDEZVOUS_NODE, &magic, &peer_key]);
        if expected.as_bytes() != &node_id {
            return Err(HnsrProbeError::Cryptography("rendezvous node ID mismatch"));
        }
        Ok(Self {
            node_id,
            services,
            peer_key,
            observed_at,
        })
    }
}

impl EndpointDelegation {
    fn parse(encoded: &[u8]) -> Result<Self, HnsrProbeError> {
        let mut reader = Reader::new(encoded);
        if reader.read_u8()? != 1 {
            return Err(HnsrProbeError::Route("unsupported delegation version"));
        }
        let authorization_id = reader.read_array()?;
        let endpoint_key = reader.read_array()?;
        let sequence = reader.read_u64_le()?;
        let issued_at = reader.read_u64_le()?;
        let expires_at = reader.read_u64_le()?;
        let max_active_circuits = read_u16_le(&mut reader)?;
        let max_bytes_per_circuit = reader.read_u64_le()?;
        let flags = read_u16_le(&mut reader)?;
        let unsigned = encoded[..reader.offset()].to_vec();
        let signature = read_signature(&mut reader, false)?;
        reader.ensure_finished()?;
        Ok(Self {
            authorization_id,
            endpoint_key,
            sequence,
            issued_at,
            expires_at,
            max_active_circuits,
            max_bytes_per_circuit,
            flags,
            unsigned,
            signature,
        })
    }

    fn verify(&self, network: &Network, now: u64) -> Result<(), HnsrProbeError> {
        if self.authorization_id.iter().any(|byte| *byte != 0)
            || self.sequence == 0
            || self.expires_at <= self.issued_at
            || self.expires_at.saturating_sub(self.issued_at) > MAX_DELEGATION_LIFETIME_SECONDS
            || now < self.issued_at
            || now >= self.expires_at
            || !(1..=32).contains(&self.max_active_circuits)
            || self.max_bytes_per_circuit == 0
            || self.flags != 0
        {
            return Err(HnsrProbeError::Route("invalid unnamed endpoint delegation"));
        }
        let magic = network.magic.to_le_bytes();
        let digest = blake2b_256(&[DOMAIN_DELEGATION, &magic, &self.unsigned]);
        verify_signature(
            &self.endpoint_key,
            digest.as_bytes(),
            &self.signature,
            "endpoint delegation",
        )
    }
}

impl RelayTicket {
    fn parse(encoded: &[u8]) -> Result<(Self, usize), HnsrProbeError> {
        let mut reader = Reader::new(encoded);
        if reader.read_u8()? != 1 {
            return Err(HnsrProbeError::Route("unsupported relay ticket version"));
        }
        let network_magic = reader.read_u32_le()?;
        let profile = read_u16_le(&mut reader)?;
        let transport = reader.read_u8()?;
        let host_type = reader.read_u8()?;
        let _host = reader.read_array::<16>()?;
        let port = read_u16_le(&mut reader)?;
        let relay_key = reader.read_array()?;
        let endpoint_key = reader.read_array()?;
        let reservation_id = reader.read_array()?;
        let issued_at = reader.read_u64_le()?;
        let expires_at = reader.read_u64_le()?;
        let max_active_circuits = read_u16_le(&mut reader)?;
        let max_bytes_per_circuit = reader.read_u64_le()?;
        let max_total_bytes = reader.read_u64_le()?;
        let flags = read_u16_le(&mut reader)?;
        let unsigned = encoded[..reader.offset()].to_vec();
        let relay_signature = read_signature(&mut reader, false)?;
        let endpoint_signature = read_signature(&mut reader, false)?;
        let consumed = reader.offset();
        Ok((
            Self {
                network_magic,
                profile,
                transport,
                host_type,
                port,
                relay_key,
                endpoint_key,
                reservation_id,
                issued_at,
                expires_at,
                max_active_circuits,
                max_bytes_per_circuit,
                max_total_bytes,
                flags,
                unsigned,
                relay_signature,
                endpoint_signature,
            },
            consumed,
        ))
    }

    fn verify(
        &self,
        network: &Network,
        endpoint_key: &[u8; 33],
        now: u64,
    ) -> Result<(), HnsrProbeError> {
        if self.network_magic != network.magic
            || self.profile != HNS_NODE_V1
            || self.transport != 0
            || !matches!(self.host_type, 1 | 2)
            || self.port == 0
            || self.endpoint_key != *endpoint_key
            || self.relay_key == self.endpoint_key
            || self.reservation_id.iter().all(|byte| *byte == 0)
            || self.expires_at <= self.issued_at
            || self.expires_at.saturating_sub(self.issued_at) > MAX_TICKET_LIFETIME_SECONDS
            || now < self.issued_at
            || now >= self.expires_at
            || !(1..=32).contains(&self.max_active_circuits)
            || self.max_bytes_per_circuit == 0
            || self.max_total_bytes < self.max_bytes_per_circuit
            || self.flags != 0
        {
            return Err(HnsrProbeError::Route("invalid relay ticket"));
        }
        let relay_digest = blake2b_256(&[DOMAIN_TICKET_RELAY, &self.unsigned]);
        verify_signature(
            &self.relay_key,
            relay_digest.as_bytes(),
            &self.relay_signature,
            "relay ticket",
        )?;
        let endpoint_digest = blake2b_256(&[
            DOMAIN_TICKET_ENDPOINT,
            &self.unsigned,
            &self.relay_signature,
        ]);
        verify_signature(
            &self.endpoint_key,
            endpoint_digest.as_bytes(),
            &self.endpoint_signature,
            "endpoint ticket confirmation",
        )
    }
}

impl RouteRecord {
    fn parse_and_verify(
        encoded: &[u8],
        network: &Network,
        now: u64,
    ) -> Result<Self, HnsrProbeError> {
        if encoded.is_empty() || encoded.len() > MAX_HNSR_RECORD_BYTES {
            return Err(HnsrProbeError::Route("record length exceeds limit"));
        }
        let mut reader = Reader::new(encoded);
        if reader.read_u8()? != 1 {
            return Err(HnsrProbeError::Route("unsupported route version"));
        }
        if reader.read_u8()? != 0 {
            return Err(HnsrProbeError::Route("route is not an unnamed authority"));
        }
        let route_key = reader.read_array()?;
        let profile = read_u16_le(&mut reader)?;
        let sequence = reader.read_u64_le()?;
        let issued_at = reader.read_u64_le()?;
        let expires_at = reader.read_u64_le()?;
        let authorization_length = usize::from(read_u16_le(&mut reader)?);
        if authorization_length != 0 {
            return Err(HnsrProbeError::Route(
                "unnamed route has authorization bytes",
            ));
        }
        let delegation_length = usize::from(read_u16_le(&mut reader)?);
        if delegation_length == 0 || delegation_length > MAX_HNSR_RECORD_BYTES {
            return Err(HnsrProbeError::Route("invalid endpoint delegation length"));
        }
        let delegation = EndpointDelegation::parse(reader.read_bytes(delegation_length)?)?;
        let ticket_count = usize::from(reader.read_u8()?);
        if !(1..=8).contains(&ticket_count) {
            return Err(HnsrProbeError::Route("invalid relay ticket count"));
        }
        let mut tickets = Vec::with_capacity(ticket_count);
        for _ in 0..ticket_count {
            let remaining = &encoded[reader.offset()..];
            let (ticket, consumed) = RelayTicket::parse(remaining)?;
            reader.read_bytes(consumed)?;
            tickets.push(ticket);
        }
        let unsigned = encoded[..reader.offset()].to_vec();
        let signature = read_signature(&mut reader, false)?;
        reader.ensure_finished()?;
        let record = Self {
            route_key,
            profile,
            sequence,
            issued_at,
            expires_at,
            delegation,
            tickets,
            unsigned,
            signature,
            raw: encoded.to_vec(),
        };
        record.verify(network, now)?;
        Ok(record)
    }

    fn verify(&self, network: &Network, now: u64) -> Result<(), HnsrProbeError> {
        if self.profile != HNS_NODE_V1
            || self.sequence == 0
            || self.expires_at <= self.issued_at
            || self.expires_at.saturating_sub(self.issued_at) > MAX_ROUTE_LIFETIME_SECONDS
            || now < self.issued_at
            || now >= self.expires_at
            || self.delegation.expires_at < self.expires_at
        {
            return Err(HnsrProbeError::Route("invalid route lifecycle or profile"));
        }
        self.delegation.verify(network, now)?;
        let magic = network.magic.to_le_bytes();
        let expected_route =
            blake2b_256(&[DOMAIN_PEER_ROUTE, &magic, &self.delegation.endpoint_key]);
        if expected_route.as_bytes() != &self.route_key {
            return Err(HnsrProbeError::Cryptography("route key mismatch"));
        }
        for ticket in &self.tickets {
            ticket.verify(network, &self.delegation.endpoint_key, now)?;
            if ticket.expires_at < self.expires_at {
                return Err(HnsrProbeError::Route("ticket expires before route"));
            }
        }
        let digest = blake2b_256(&[DOMAIN_ROUTE, &self.unsigned]);
        verify_signature(
            &self.delegation.endpoint_key,
            digest.as_bytes(),
            &self.signature,
            "route record",
        )
    }
}

impl HnsrProbeReport {
    pub fn to_json(&self) -> String {
        let relay_keys = self
            .relay_keys
            .iter()
            .map(|key| format!(r#""{}""#, hex::encode(key)))
            .collect::<Vec<_>>()
            .join(",");
        let opcodes = self
            .observed_opcodes
            .iter()
            .map(|opcode| format!(r#""{}""#, opcode))
            .collect::<Vec<_>>()
            .join(",");
        format!(
            r#"{{"schema":1,"result":"pass","implementation":"native-rust","network":"{}","bootstrap":"{}","outer":{{"versionVerack":true,"services":{},"remoteHeight":{},"rendezvousService":true}},"hnsr":{{"packetType":"0xf3","findNodeContacts":{},"sampledRecords":{},"exactRecords":{},"observedOpcodes":[{}]}},"route":{{"key":"{}","sequence":{},"endpointKey":"{}","relayTickets":{},"relayKeys":[{}],"delegationVerified":true,"ticketsVerified":true,"routeSignatureVerified":true,"networkBound":true}},"elapsedMs":{}}}"#,
            self.network,
            json_escape(&self.bootstrap),
            self.remote_services,
            self.remote_height,
            self.contacts,
            self.sampled_records,
            self.exact_records,
            opcodes,
            hex::encode(self.route_key),
            self.route_sequence,
            hex::encode(self.endpoint_key),
            self.relay_keys.len(),
            relay_keys,
            self.elapsed_millis,
        )
    }
}

pub fn probe_hnsr(
    network: Network,
    bootstrap: SocketAddr,
    timeout: Duration,
) -> Result<HnsrProbeReport, HnsrProbeError> {
    if network.name != "regtest" {
        return Err(HnsrProbeError::NetworkDisabled);
    }
    let started = Instant::now();
    let mut connection = PeerConnection::connect(bootstrap, network.clone(), timeout)?;
    let local_version = VersionPacket {
        agent: concat!("/hns-dane-hnsr:", env!("CARGO_PKG_VERSION"), "/").to_owned(),
        ..VersionPacket::default()
    };
    let mut session = HeaderSyncSession::new(local_version);
    let remote = connection.handshake(&mut session)?;
    if remote.services & EXPERIMENTAL_HNSR_RENDEZVOUS_SERVICE == 0 {
        return Err(HnsrProbeError::MissingRendezvousService);
    }

    let now = unix_time()?;
    let mut observed = Vec::new();
    let target: [u8; 32] = random_array()?;
    let mut find_body = Vec::with_capacity(33);
    find_body.extend(target);
    find_body.push(MAX_HNSR_CONTACTS as u8);
    let nodes = request_hnsr(
        &mut connection,
        HnsrOpcode::FindNode,
        find_body,
        HnsrOpcode::Nodes,
        &mut observed,
    )?;
    let contacts = parse_nodes(&nodes.body, &network, now)?;

    let seed: [u8; 32] = random_array()?;
    let mut sample_body = Vec::with_capacity(33);
    sample_body.push(8);
    sample_body.extend(seed);
    let sampled = request_hnsr(
        &mut connection,
        HnsrOpcode::SampleRoutes,
        sample_body,
        HnsrOpcode::Routes,
        &mut observed,
    )?;
    let sampled_records = parse_routes(&sampled.body, &network, now)?;
    let route = sampled_records
        .first()
        .ok_or(HnsrProbeError::Response("no sampled unnamed routes"))?;

    let mut exact_body = Vec::with_capacity(33);
    exact_body.extend(route.route_key);
    exact_body.push(8);
    let exact = request_hnsr(
        &mut connection,
        HnsrOpcode::GetRoute,
        exact_body,
        HnsrOpcode::Routes,
        &mut observed,
    )?;
    let exact_records = parse_routes(&exact.body, &network, now)?;
    let exact_route = exact_records
        .iter()
        .find(|candidate| candidate.raw == route.raw)
        .ok_or(HnsrProbeError::ExactRouteMissing)?;

    Ok(HnsrProbeReport {
        network: network.name,
        bootstrap: bootstrap.to_string(),
        remote_services: remote.services,
        remote_height: remote.height.0,
        contacts: contacts.len(),
        sampled_records: sampled_records.len(),
        exact_records: exact_records.len(),
        route_key: exact_route.route_key,
        route_sequence: exact_route.sequence,
        endpoint_key: exact_route.delegation.endpoint_key,
        relay_keys: exact_route
            .tickets
            .iter()
            .map(|ticket| ticket.relay_key)
            .collect(),
        observed_opcodes: observed,
        elapsed_millis: started.elapsed().as_millis(),
    })
}

pub fn probe_hnsr_json(network: &str, bootstrap: &str, timeout: Duration) -> String {
    let network_kind = NetworkKind::parse(network);
    let endpoint = bootstrap.parse::<SocketAddr>();
    let result = match (network_kind, endpoint) {
        (Some(kind), Ok(endpoint)) => probe_hnsr(kind.network(), endpoint, timeout),
        _ => Err(HnsrProbeError::InvalidBootstrap),
    };
    match result {
        Ok(report) => report.to_json(),
        Err(error) => format!(
            r#"{{"schema":1,"result":"error","implementation":"native-rust","network":"{}","bootstrap":"{}","error":"{}"}}"#,
            json_escape(network),
            json_escape(bootstrap),
            json_escape(&error.to_string()),
        ),
    }
}

fn request_hnsr<T: std::io::Read + std::io::Write>(
    connection: &mut PeerConnection<T>,
    request_opcode: HnsrOpcode,
    body: Vec<u8>,
    response_opcode: HnsrOpcode,
    observed: &mut Vec<&'static str>,
) -> Result<HnsrEnvelope, HnsrProbeError> {
    let context = random_context()?;
    let request = HnsrEnvelope {
        opcode: request_opcode as u8,
        context,
        body,
    };
    connection.send_packet(&Packet::Unknown {
        packet_type: EXPERIMENTAL_HNSR_PACKET,
        payload: request.encode()?,
    })?;
    observed.push(request_opcode.name());

    for _ in 0..MAX_HNSR_RESPONSE_PACKETS {
        match connection.receive_packet()? {
            Packet::Ping(nonce) => connection.send_packet(&Packet::Pong(nonce))?,
            Packet::Unknown {
                packet_type: EXPERIMENTAL_HNSR_PACKET,
                payload,
            } => {
                let response = HnsrEnvelope::decode(&payload)?;
                if response.context != context {
                    continue;
                }
                if response.opcode == HnsrOpcode::Error as u8 {
                    observed.push(HnsrOpcode::Error.name());
                    return Err(parse_peer_error(&response.body)?);
                }
                if response.opcode != response_opcode as u8 {
                    return Err(HnsrProbeError::Response("unexpected correlated opcode"));
                }
                observed.push(response_opcode.name());
                return Ok(response);
            }
            Packet::Version(_)
            | Packet::Verack
            | Packet::Pong(_)
            | Packet::GetAddr
            | Packet::Addr(_)
            | Packet::GetHeaders(_)
            | Packet::Headers(_)
            | Packet::SendHeaders
            | Packet::GetProof(_)
            | Packet::Proof(_)
            | Packet::GetDnsRelay(_)
            | Packet::DnsRelay(_)
            | Packet::Unknown { .. } => {}
        }
    }
    Err(HnsrProbeError::Response("response packet limit exceeded"))
}

fn parse_nodes(
    body: &[u8],
    network: &Network,
    now: u64,
) -> Result<Vec<RendezvousContact>, HnsrProbeError> {
    let mut reader = Reader::new(body);
    let count = usize::from(reader.read_u8()?);
    if count > MAX_HNSR_CONTACTS {
        return Err(HnsrProbeError::Response("too many rendezvous contacts"));
    }
    let mut contacts = Vec::with_capacity(count);
    for _ in 0..count {
        contacts.push(RendezvousContact::parse(&mut reader, network, now)?);
    }
    reader.ensure_finished()?;
    Ok(contacts)
}

fn parse_routes(
    body: &[u8],
    network: &Network,
    now: u64,
) -> Result<Vec<RouteRecord>, HnsrProbeError> {
    let mut reader = Reader::new(body);
    let count = usize::from(reader.read_u8()?);
    if count > MAX_HNSR_RECORDS {
        return Err(HnsrProbeError::Response("too many route records"));
    }
    let mut records = Vec::with_capacity(count);
    for _ in 0..count {
        let length = usize::from(read_u16_le(&mut reader)?);
        if length == 0 || length > MAX_HNSR_RECORD_BYTES {
            return Err(HnsrProbeError::Response("invalid route record length"));
        }
        records.push(RouteRecord::parse_and_verify(
            reader.read_bytes(length)?,
            network,
            now,
        )?);
    }
    reader.ensure_finished()?;
    Ok(records)
}

fn parse_peer_error(body: &[u8]) -> Result<HnsrProbeError, HnsrProbeError> {
    let mut reader = Reader::new(body);
    let code = read_u16_le(&mut reader)?;
    let length = usize::from(reader.read_u8()?);
    if length > 128 {
        return Err(HnsrProbeError::Response("peer error detail exceeds limit"));
    }
    let detail = std::str::from_utf8(reader.read_bytes(length)?)
        .map_err(|_| HnsrProbeError::Response("peer error detail is not UTF-8"))?
        .to_owned();
    reader.ensure_finished()?;
    Ok(HnsrProbeError::PeerError { code, detail })
}

fn verify_signature(
    public_key: &[u8; 33],
    digest: &[u8; 32],
    encoded_signature: &[u8],
    label: &'static str,
) -> Result<(), HnsrProbeError> {
    let verifying_key = VerifyingKey::from_sec1_bytes(public_key)
        .map_err(|_| HnsrProbeError::Cryptography("invalid secp256k1 public key"))?;
    let signature = Signature::from_der(encoded_signature)
        .map_err(|_| HnsrProbeError::Cryptography("noncanonical DER signature"))?;
    if signature.normalize_s().is_some() {
        return Err(HnsrProbeError::Cryptography("high-S signature"));
    }
    verifying_key
        .verify_prehash(digest, &signature)
        .map_err(|_| HnsrProbeError::Cryptography(label))
}

fn read_signature(reader: &mut Reader<'_>, allow_empty: bool) -> Result<Vec<u8>, HnsrProbeError> {
    let length = usize::from(reader.read_u8()?);
    if length > MAX_HNSR_SIGNATURE_BYTES || (!allow_empty && length == 0) {
        return Err(HnsrProbeError::Route("invalid signature length"));
    }
    Ok(reader.read_bytes(length)?.to_vec())
}

fn read_u16_le(reader: &mut Reader<'_>) -> Result<u16, ParseError> {
    Ok(u16::from_le_bytes(reader.read_array()?))
}

fn random_context() -> Result<[u8; 8], HnsrProbeError> {
    loop {
        let context = random_array()?;
        if context.iter().any(|byte| *byte != 0) {
            return Ok(context);
        }
    }
}

fn random_array<const N: usize>() -> Result<[u8; N], HnsrProbeError> {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).map_err(|_| HnsrProbeError::RandomnessUnavailable)?;
    Ok(bytes)
}

fn unix_time() -> Result<u64, HnsrProbeError> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .map_err(|_| HnsrProbeError::Response("system time predates Unix epoch"))
}

fn json_escape(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            character if character <= '\u{001f}' => {
                use std::fmt::Write as _;
                let _ = write!(escaped, "\\u{:04x}", character as u32);
            }
            character => escaped.push(character),
        }
    }
    escaped
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn envelope_round_trip_preserves_private_packet_shape() {
        let envelope = HnsrEnvelope {
            opcode: HnsrOpcode::SampleRoutes as u8,
            context: [7u8; 8],
            body: vec![1, 2, 3],
        };
        let encoded = envelope.encode().unwrap();
        assert_eq!(encoded.len(), 15);
        assert_eq!(HnsrEnvelope::decode(&encoded).unwrap(), envelope);
    }

    #[test]
    fn envelope_rejects_zero_context_and_reserved_flags() {
        let zero = HnsrEnvelope {
            opcode: HnsrOpcode::GetRoute as u8,
            context: [0u8; 8],
            body: Vec::new(),
        };
        assert!(zero.encode().is_err());

        let mut encoded = HnsrEnvelope {
            opcode: HnsrOpcode::GetRoute as u8,
            context: [1u8; 8],
            body: Vec::new(),
        }
        .encode()
        .unwrap();
        encoded[2] = 1;
        assert!(HnsrEnvelope::decode(&encoded).is_err());
    }

    #[test]
    fn native_probe_is_fail_closed_outside_regtest() {
        let endpoint: SocketAddr = "127.0.0.1:1".parse().unwrap();
        assert_eq!(
            probe_hnsr(
                NetworkKind::Mainnet.network(),
                endpoint,
                Duration::from_millis(1),
            )
            .unwrap_err(),
            HnsrProbeError::NetworkDisabled,
        );
    }

    #[test]
    fn error_json_escapes_untrusted_inputs() {
        let json = probe_hnsr_json("bad\"network", "bad\nendpoint", Duration::from_millis(1));
        assert!(json.contains(r#""result":"error""#));
        assert!(json.contains(r#"bad\"network"#));
        assert!(json.contains(r#"bad\nendpoint"#));
    }
}
