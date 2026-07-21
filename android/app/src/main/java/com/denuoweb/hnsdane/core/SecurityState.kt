package com.denuoweb.hnsdane.core

enum class SecurityState {
    Syncing,
    Loading,
    HnsVerified,
    HnsCompatibility,
    HnsViaAuthoritativeDoh,
    HnsViaAuthoritativeDns53,
    HnsViaP2pDnsRelay,
    HnsViaP2pOdoh,
    HnsViaThirdPartyDoh,
    DaneVerified,
    DaneCompatibility,
    DaneViaAuthoritativeDoh,
    DaneViaAuthoritativeDns53,
    DaneViaP2pDnsRelay,
    DaneViaP2pOdoh,
    DaneViaThirdPartyDoh,
    StatelessDane,
    DaneViaIcannDoh,
    WebPkiOnly,
    MixedPolicy,
    ValidationFailed,
    ProofUnavailable,
}

enum class HnsPageTlsPolicy {
    Dane,
    WebPkiFallback,
}

enum class HnsPageResolverPolicy {
    HnsDohCompatibility,
}

enum class HnsPageSecurityPath {
    DaneAuthoritativeDoh,
    DaneAuthoritativeDns53,
    DaneThirdPartyDoh,
    StatelessDane,
    DaneIcannDoh,
    HnsAuthoritativeDoh,
    HnsAuthoritativeDns53,
    HnsThirdPartyDoh,
    DaneP2pDnsRelay,
    HnsP2pDnsRelay,
    DaneP2pOdoh,
    HnsP2pOdoh,
    ;

    companion object {
        fun fromHeaderValue(value: String?): HnsPageSecurityPath? =
            when (value?.trim()?.lowercase()) {
                "dane-authoritative-doh" -> DaneAuthoritativeDoh
                "dane-authoritative-dns53" -> DaneAuthoritativeDns53
                "dane-third-party-doh" -> DaneThirdPartyDoh
                "stateless-dane" -> StatelessDane
                "dane-icann-doh" -> DaneIcannDoh
                "hns-authoritative-doh" -> HnsAuthoritativeDoh
                "hns-authoritative-dns53" -> HnsAuthoritativeDns53
                "hns-third-party-doh" -> HnsThirdPartyDoh
                "dane-p2p-dns-relay" -> DaneP2pDnsRelay
                "hns-p2p-dns-relay" -> HnsP2pDnsRelay
                "dane-p2p-odoh" -> DaneP2pOdoh
                "hns-p2p-odoh" -> HnsP2pOdoh
                else -> null
            }
    }
}
