# Experimental HNS P2P Oblivious DNS requester

Status: complete direct-locator proof of concept. The service bit and packet
identifier remain private experiment values until the corresponding HIP is
assigned and accepted.

The native browser runtime implements the requester side of the Handshake P2P
ODoH profile on top of the prerequisite P2P DNS relay. It connects to an
explicit proxy over the ordinary HNS TCP transport, requires the proxy's live
version handshake to advertise `0x20000000`, obtains a target-identity-signed
configuration, encrypts the admitted DNS query with RFC 9230 HPKE, and accepts
only a correlated encrypted response. The proxy and target must be distinct.

`RuntimePolicy::experimental_p2p_odoh` takes an `OdohRuntimeConfig` containing:

- the numeric proxy socket;
- the numeric direct Brontide target socket; and
- the target's 33-byte compressed secp256k1 peer identity key.

The direct locator is checked against the signed target configuration. On
mainnet and testnet it must pass the existing public endpoint policy; regtest
permits private addresses for isolated testing. The requester permits one
alternate proxy, bounds a complete exchange to ten seconds, checks target
record network/lifetime/signature/low-S canonicality, enforces the base relay
query profile, and validates response transaction/question correlation before
returning bytes to the resolver.

ODoH is only a transport beneath the established validation pipeline:

```text
verified headers and Urkel proof -> proof-derived NS/DS
  -> RFC 9230 query through proxy -> authenticated target recursion
  -> local DNSSEC -> HTTPS/SVCB -> TLSA/DANE
```

Neither the proxy nor the target is trusted for DNS authenticity. Resolution
traces use `p2p_odoh`, with `dane-p2p-odoh` and `hns-p2p-odoh` security paths
exposed consistently by the Rust runtime, Android status bridge, and iOS ABI.

The checked-in cross-implementation vector covers direct-locator encoding,
RFC 9230 configuration and key-ID encoding, padded query plaintext, response
KDF output, and the complete target-identity-signed configuration record. Rust
verifies the vector generated with the sibling patched `hsd` implementation.

## Reproducible regtest acceptance

With the sibling `hsd` branch at `../hsd`:

```sh
HSD_REPO=../hsd ./scripts/test-experimental-p2p-dns-relay-full.sh --preflight
HSD_REPO=../hsd ./scripts/test-experimental-p2p-dns-relay-full.sh
```

The full runner creates four independent real `hsd` prefixes. It uses
`hsd-relay-bad` as the requester-facing ODoH proxy and the separately
authenticated Brontide listener on `hsd-owner-good` as the target. It mines and
registers `relaytest`, proves the current Urkel state on all four nodes, serves
a signed delegated zone, blocks browser access to authoritative and external
port 53, and requires the native browser runtime to complete HTTPS through:

- a signed ODoH target configuration;
- separate proxy and target hops;
- locally secure DNSSEC;
- locally verified TLSA/DANE; and
- zero contacts to the configured legacy DoH sentinel.

The final result is written to `full-tier-result.json`; the resolution source
must be `p2p_odoh`, `odoh.verified` must be true, and proxy and target sockets
must differ. `full-tier-response.txt`, `full-tier-proof.json`, and the remaining
aggregate artifacts preserve the fail-closed evidence for the run.

The direct-locator requester is the complete mandatory reference profile.
HNSR locators, persistent target-key storage, multi-target policy, config
caching, and outer traffic-analysis padding are optional extensions and do not
alter direct-locator conformance.
