import Foundation

enum BrowserCoreError: LocalizedError, Equatable {
    case runtimeUnavailable(String)
    case invalidAddress(String)
    case unsupportedAddress
    case proxyStartFailed(String)
    case invalidProxyEndpoint

    var errorDescription: String? {
        switch self {
        case .runtimeUnavailable(let message):
            return "The Handshake runtime is unavailable: \(message)"
        case .invalidAddress(let message):
            return message
        case .unsupportedAddress:
            return "Only HTTP and HTTPS addresses can be opened."
        case .proxyStartFailed(let message):
            return "The secure browser proxy could not start: \(message)"
        case .invalidProxyEndpoint:
            return "The secure browser proxy returned an invalid loopback endpoint."
        }
    }
}

enum BrowserHandshakeNetwork: String, CaseIterable, Equatable, Hashable, Sendable {
    case mainnet
    case testnet
    case regtest

    var title: String {
        switch self {
        case .mainnet: "Mainnet"
        case .testnet: "Testnet"
        case .regtest: "Regtest"
        }
    }

    var summary: String {
        switch self {
        case .mainnet: "Public Handshake network."
        case .testnet: "Public Handshake test network."
        case .regtest: "Local regression-test network."
        }
    }
}

enum BrowserThemeMode: String, CaseIterable, Equatable, Sendable {
    case system
    case light
    case dark

    var title: String {
        switch self {
        case .system: "Follow system"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    var summary: String {
        switch self {
        case .system: "Follows your iOS system theme."
        case .light: "Light theme."
        case .dark: "Dark theme."
        }
    }
}

enum BrowserSettingsPreferences {
    static let defaultHomepage = "https://denuoweb.com/work/hns-dane-browser"

    private static let homepageKey = "browser.settings.homepage"
    private static let themeKey = "browser.settings.theme"
    private static let networkKey = "browser.settings.handshakeNetwork"

    static var homepage: String {
        let saved = UserDefaults.standard.string(forKey: homepageKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return saved.isEmpty ? defaultHomepage : saved
    }

    static var themeMode: BrowserThemeMode {
        BrowserThemeMode(
            rawValue: UserDefaults.standard.string(forKey: themeKey) ?? ""
        ) ?? .system
    }

    static var handshakeNetwork: BrowserHandshakeNetwork {
        BrowserHandshakeNetwork(
            rawValue: UserDefaults.standard.string(forKey: networkKey) ?? ""
        ) ?? .mainnet
    }

    static func saveHomepage(_ homepage: String) {
        UserDefaults.standard.set(homepage, forKey: homepageKey)
    }

    static func resetHomepage() {
        UserDefaults.standard.removeObject(forKey: homepageKey)
    }

    static func saveThemeMode(_ mode: BrowserThemeMode) {
        UserDefaults.standard.set(mode.rawValue, forKey: themeKey)
    }

    static func saveHandshakeNetwork(_ network: BrowserHandshakeNetwork) {
        UserDefaults.standard.set(network.rawValue, forKey: networkKey)
    }
}

struct BrowserHistoryEntry: Codable, Equatable {
    let url: String
    let title: String
    let visitedAt: Date
}

enum BrowserHistoryStore {
    private static let key = "browser.history.entries.v1"
    private static let maximumEntryCount = 250

    static var entries: [BrowserHistoryEntry] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let values = try? JSONDecoder().decode([BrowserHistoryEntry].self, from: data) else {
            return []
        }
        return Array(values.prefix(maximumEntryCount))
    }

    static func record(url: String, title: String = "", visitedAt: Date = Date()) {
        let normalized = url.trimmingCharacters(in: .whitespacesAndNewlines)
        let lowercased = normalized.lowercased()
        guard !normalized.isEmpty,
              normalized.count <= 16 * 1024,
              lowercased != "about:blank",
              lowercased != BrowserSettingsPreferences.defaultHomepage.lowercased(),
              !lowercased.hasPrefix("data:"),
              !lowercased.hasPrefix("blob:") else {
            return
        }
        let entry = BrowserHistoryEntry(
            url: normalized,
            title: String(title.trimmingCharacters(in: .whitespacesAndNewlines).prefix(512)),
            visitedAt: visitedAt
        )
        let updated = [entry] + entries.filter { $0.url != normalized }
        if let data = try? JSONEncoder().encode(Array(updated.prefix(maximumEntryCount))) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    @discardableResult
    static func clear() -> Int {
        let count = entries.count
        UserDefaults.standard.removeObject(forKey: key)
        return count
    }
}

struct BrowserDownloadRecord: Codable, Equatable {
    let fileURL: URL
    let sourceURL: String
    let savedAt: Date
}

enum BrowserDownloadStore {
    private static let key = "browser.download.records.v1"
    private static let maximumRecordCount = 100

    static var records: [BrowserDownloadRecord] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let values = try? JSONDecoder().decode([BrowserDownloadRecord].self, from: data) else {
            return []
        }
        return Array(values.prefix(maximumRecordCount))
    }

    static func record(fileURL: URL, sourceURL: String = "", savedAt: Date = Date()) {
        let entry = BrowserDownloadRecord(
            fileURL: fileURL,
            sourceURL: sourceURL,
            savedAt: savedAt
        )
        let updated = [entry] + records.filter { $0.fileURL != fileURL }
        if let data = try? JSONEncoder().encode(Array(updated.prefix(maximumRecordCount))) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    @discardableResult
    static func clear() -> Int {
        let count = records.count
        UserDefaults.standard.removeObject(forKey: key)
        return count
    }
}

struct BrowserGatewayEvent: Codable, Equatable {
    let timestamp: Date
    let stage: String
    let host: String
    let status: Int
    let reason: String
}

enum BrowserGatewayEventStore {
    private static let key = "browser.gateway.events.v1"
    private static let maximumEventCount = 250

    static var entries: [BrowserGatewayEvent] {
        guard let data = UserDefaults.standard.data(forKey: key),
              let values = try? JSONDecoder().decode([BrowserGatewayEvent].self, from: data) else {
            return []
        }
        return Array(values.prefix(maximumEventCount))
    }

    static func record(
        stage: String,
        host: String,
        status: Int,
        reason: String,
        timestamp: Date = Date()
    ) {
        let event = BrowserGatewayEvent(
            timestamp: timestamp,
            stage: String(stage.prefix(128)),
            host: String(host.prefix(1_024)),
            status: status,
            reason: String(reason.prefix(4_096))
        )
        let updated = [event] + entries
        if let data = try? JSONEncoder().encode(Array(updated.prefix(maximumEventCount))) {
            UserDefaults.standard.set(data, forKey: key)
        }
    }

    @discardableResult
    static func clear() -> Int {
        let count = entries.count
        UserDefaults.standard.removeObject(forKey: key)
        return count
    }

    static func formatted(_ events: [BrowserGatewayEvent]? = nil) -> String {
        let values = events ?? entries
        guard !values.isEmpty else { return "No recent gateway events." }
        let formatter = ISO8601DateFormatter()
        return values.map { event in
            """
            Timestamp: \(formatter.string(from: event.timestamp))
            Stage: \(event.stage)
            Host: \(event.host.isEmpty ? "Unknown" : event.host)
            Status: \(event.status)
            Reason: \(event.reason)
            """
        }.joined(separator: "\n\n")
    }
}

enum BrowserHostKind: Equatable, Sendable {
    case handshake
    case icann
    case search
}

enum BrowserProxyScope: Equatable, Sendable {
    case icann
    case handshakeRoot(String)
}

struct BrowserDestination: Equatable, Sendable {
    let url: URL
    let canonicalHost: String
    let hostKind: BrowserHostKind
    let proxyScope: BrowserProxyScope

    var isHandshake: Bool { hostKind == .handshake }
}

struct BrowserProxyEndpoint: Equatable, Sendable {
    let host: String
    let port: UInt16
    let realm: String
    let username: String
    let password: String

    var isNumericIPv4Loopback: Bool { host == "127.0.0.1" }
}

enum BrowserSecurityLevel: Equatable, Sendable {
    case pending
    case webPKI
    case insecure
    case handshakeDANE
    case handshakeFallback
    case blocked
}

enum MainFrameAdmissionDecision: Equatable {
    case allow
    case rotateProxy
    case blockNonIdempotentReplay
}

struct MainFrameAdmissionPolicy {
    func evaluate(
        activeScope: BrowserProxyScope?,
        destinationScope: BrowserProxyScope,
        httpMethod: String
    ) -> MainFrameAdmissionDecision {
        if activeScope == destinationScope { return .allow }
        let method = httpMethod.uppercased()
        return (method == "GET" || method == "HEAD")
            ? .rotateProxy
            : .blockNonIdempotentReplay
    }
}

struct NavigationReplayPolicy {
    func allowsAutomaticReplay(httpMethod: String?) -> Bool {
        let method = httpMethod?.uppercased() ?? "GET"
        return method == "GET" || method == "HEAD"
    }
}

struct BrowserSecuritySummary: Equatable, Sendable {
    let level: BrowserSecurityLevel
    let detail: String

    static let pending = BrowserSecuritySummary(
        level: .pending,
        detail: "Waiting for a verified response"
    )
}

struct BrowserSyncSummary: Equatable, Sendable {
    let headline: String
    let detail: String
    let status: String
    let network: String?
    let attempted: Int
    let successful: Int
    let accepted: Int
    let failed: Int
    let peerCount: Int
    let peerGroups: Int
    let bestHeight: UInt64?
    let bestPeerHeight: UInt64?
    let estimatedTipHeight: UInt64?
    let resourceCacheEntries: Int
    let resourceCacheBytes: UInt64
    let resourceCacheEvicted: Int
    let error: String?

    init(
        headline: String,
        detail: String,
        status: String = "unavailable",
        network: String? = nil,
        attempted: Int = 0,
        successful: Int = 0,
        accepted: Int = 0,
        failed: Int = 0,
        peerCount: Int = 0,
        peerGroups: Int = 0,
        bestHeight: UInt64? = nil,
        bestPeerHeight: UInt64? = nil,
        estimatedTipHeight: UInt64? = nil,
        resourceCacheEntries: Int = 0,
        resourceCacheBytes: UInt64 = 0,
        resourceCacheEvicted: Int = 0,
        error: String? = nil
    ) {
        self.headline = headline
        self.detail = detail
        self.status = status
        self.network = network
        self.attempted = attempted
        self.successful = successful
        self.accepted = accepted
        self.failed = failed
        self.peerCount = peerCount
        self.peerGroups = peerGroups
        self.bestHeight = bestHeight
        self.bestPeerHeight = bestPeerHeight
        self.estimatedTipHeight = estimatedTipHeight
        self.resourceCacheEntries = resourceCacheEntries
        self.resourceCacheBytes = resourceCacheBytes
        self.resourceCacheEvicted = resourceCacheEvicted
        self.error = error
    }

    var requiresRetry: Bool {
        error != nil || ["error", "peer_failed", "seed_failed"].contains(status)
    }

    var targetHeight: UInt64? { bestPeerHeight ?? estimatedTipHeight }

    var isBehind: Bool {
        guard let bestHeight, let targetHeight else { return false }
        return targetHeight > bestHeight
    }

    /// Mirrors Android's currentness interpretation. A successful sync tick
    /// reports `synced` after it accepts headers and reaches its target; a
    /// partially attempted tick is also current when its recorded target is
    /// not ahead. Neither state should be forced through another foreground
    /// catch-up cycle merely because it is not spelled `up_to_date`.
    var isCaughtUp: Bool {
        status == "up_to_date"
            || (["synced", "attempted"].contains(status) && !isBehind)
    }

    static let unavailable = BrowserSyncSummary(
        headline: "Sync unavailable",
        detail: "The Handshake runtime did not return sync status."
    )

    static func failure(_ error: Error) -> BrowserSyncSummary {
        BrowserSyncSummary(
            headline: "Header sync needs attention",
            detail: error.localizedDescription,
            status: "error",
            error: error.localizedDescription
        )
    }
}

struct BrowserProofDetails: Equatable, Sendable {
    let headline: String
    let detail: String
    let host: String
    let name: String?
    let network: String?
    let nameHash: String?
    let hnsProof: String
    let proofStatus: String
    let secure: Bool?
    let exists: Bool?
    let treeRoot: String?
    let blockHeight: UInt64?
    let cacheStatus: String
    let recordTypes: [String]
    let error: String?
    let formattedJSON: String
}

enum BrowserDiagnosticReports {
    static func domainSetup(_ details: BrowserProofDetails) -> String {
        let types = Set(details.recordTypes.map { $0.uppercased() })
        let hasNS = types.contains("NS")
        let hasAddress = types.contains("A") || types.contains("AAAA")
        let hasDS = types.contains("DS")
        let hasTXT = types.contains("TXT")
        let rootName = details.name ?? details.host.split(separator: ".").last.map(String.init)
            ?? details.host

        let problem: String
        switch details.proofStatus {
        case "unavailable":
            problem = "Proof data is unavailable (cache: \(details.cacheStatus))."
        case "not_found":
            problem = "The Handshake name was not found."
        case "verified" where !hasNS && !hasAddress:
            problem = "The proof is verified, but no delegation or address records are present."
        case "verified" where hasNS && !hasAddress:
            problem = "Delegation exists, but no A or AAAA glue/address record is present."
        case "verified" where hasAddress && !hasDS:
            problem = "Address data exists, but no DS record advertises delegated DNSSEC."
        case "verified":
            problem = "The proof contains usable delegation or address data."
        default:
            problem = "The proof is not verified (status: \(details.proofStatus))."
        }

        let suggestedFix: String
        switch details.proofStatus {
        case "unavailable":
            suggestedFix = "Sync Handshake headers and retry after the resolver is current."
        case "not_found":
            suggestedFix = "Confirm that \(rootName) is registered and has a published resource."
        case "verified" where !hasNS && !hasAddress:
            suggestedFix = "Publish NS delegation or A/AAAA records for \(rootName)."
        case "verified" where hasNS && !hasAddress:
            suggestedFix = "Add reachable A/AAAA glue or authoritative address records."
        case "verified" where !hasDS:
            suggestedFix = "Publish a DS record and configure DNSSEC before relying on DANE."
        case "verified":
            suggestedFix = "Verify the web server, TLSA owner, and certificate from the inspector."
        default:
            suggestedFix = "Correct the proof or delegation error, then resolve the name again."
        }

        let records = resourceRecordLines(in: details.formattedJSON)
        let txtWarning = hasTXT
            ? "\n\nNote: TXT records are informational and do not replace NS, DS, A, or AAAA setup."
            : ""
        return """
        HNS DOMAIN SETUP REPORT

        Host: \(details.host)
        Root name: \(rootName)
        Proof status: \(details.proofStatus)
        Cache status: \(details.cacheStatus)
        Record types: \(details.recordTypes.isEmpty ? "None" : details.recordTypes.joined(separator: ", "))

        CURRENT PROBLEM
        \(problem)

        SUGGESTED FIX
        \(suggestedFix)

        CHECKLIST
        • Publish the intended Handshake resource for \(rootName).
        • Verify authoritative DNS and address records.
        • Verify DNSSEC/DS before relying on TLSA.
        • Check the TLSA owner for \(details.host).

        DECODED RECORDS
        \(records)\(txtWarning)

        RAW JSON
        \(details.formattedJSON)
        """
    }

    static func tlsaDANE(url: String, traceJSON: String?) -> String {
        guard let traceJSON, !traceJSON.isEmpty,
              let data = traceJSON.data(using: .utf8),
              let trace = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tls = trace["tls"] as? [String: Any] else {
            return "No TLSA/DANE resolution trace is available for the current page."
        }
        let dane = tls["dane"] as? [String: Any] ?? [:]
        let certificate = tls["certificate"] as? [String: Any] ?? [:]
        let records = (tls["records"] as? [[String: Any]] ?? []).map { record in
            "Usage: \(value(record["usage"])) · Selector: \(value(record["selector"])) · "
                + "Matching: \(value(record["matching"]))\n"
                + "Association data: \(value(record["associationDataHex"]))"
        }.joined(separator: "\n\n")

        return """
        TLSA / DANE INSPECTOR

        URL: \(url.isEmpty ? value(trace["url"]) : url)
        Host: \(value(trace["host"]))
        TLS mode: \(value(tls["mode"]))
        TLSA owner: \(value(tls["tlsaOwner"]))
        TLSA status: \(value(tls["tlsaStatus"]))
        TLSA found: \(value(tls["tlsaFound"]))
        TLSA source: \(value(tls["tlsaSource"]))
        DNSSEC secure: \(value(tls["dnssecSecure"]))
        DANE decision: \(value(dane["decision"]))
        Matched usage: \(value(dane["matchedUsage"], fallback: "None"))
        Certificate match: \(value(dane["certificateMatch"]))
        WebPKI fallback: \(value(dane["webPkiFallback"]))
        WebPKI status: \(value(certificate["webPkiStatus"]))
        Certificate SHA-256: \(value(certificate["endEntitySha256"]))
        SPKI SHA-256: \(value(certificate["spkiSha256"]))
        Intermediate certificates: \(value(certificate["intermediateCount"]))

        TLSA RECORDS
        \(records.isEmpty ? "None" : records)

        SPKI DER
        \(value(certificate["spkiDerHex"], fallback: "Unavailable"))

        RAW JSON
        \(traceJSON)
        """
    }

    private static func resourceRecordLines(in json: String) -> String {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let records = object["resourceRecords"] as? [[String: Any]],
              !records.isEmpty else {
            return "None"
        }
        return records.map { record in
            "\(value(record["type"])) \(value(record["name"])) "
                + "\(value(record["rdataHex"]))"
        }.joined(separator: "\n")
    }

    private static func value(_ raw: Any?, fallback: String = "Unknown") -> String {
        switch raw {
        case let value as String where !value.isEmpty:
            return value
        case let value as Bool:
            return value ? "true" : "false"
        case let value as NSNumber:
            return value.stringValue
        default:
            return fallback
        }
    }
}

protocol BrowserProxySession: AnyObject {
    var endpoint: BrowserProxyEndpoint { get }
    var latestResolutionTraceJSON: String? { get }

    func clearResolutionTrace()

    /// Must be nonblocking and safe to call repeatedly from the main actor.
    func requestStop()

    /// Blocks until the listener and connection workers exit, then releases the native handle.
    func joinAndDestroy()

    func acceptsProxyChallenge(
        host: String,
        port: Int,
        realm: String?,
        authenticationMethod: String
    ) -> Bool

    func matchesLocalCertificate(host: String, leafCertificateDER: Data) -> Bool

    func takeMainFrameSecurityStatus(host: String) -> BrowserSecuritySummary?
}

extension BrowserProxySession {
    var latestResolutionTraceJSON: String? { nil }
    func clearResolutionTrace() {}
}

protocol BrowserRuntime: AnyObject {
    /// Parses user input or an absolute WebKit URL and classifies its host in the Rust policy.
    func classifyNavigation(_ rawValue: String) throws -> BrowserDestination

    /// Classifies a protection-space host in Rust without a duplicated platform TLD list.
    func classifyHost(_ host: String) -> BrowserHostKind

    func canonicalHost(_ host: String) -> String?

    /// Starts a whole-WebKit proxy. This can block and must run off the main actor.
    func startWholeWebKitProxy(hnsScopeRoot: String?) throws -> BrowserProxySession

    /// Imports the bounded, uncompressed mainnet bootstrap snapshot at a private file path.
    func installHeaderSnapshot(at path: String) throws

    /// Publishes a live resolver policy. Native code revokes every older proxy generation.
    @discardableResult
    func updatePolicy(_ policy: BrowserRuntimePolicy) throws -> UInt64

    func syncOnce() throws -> BrowserSyncSummary

    func syncSummary() -> BrowserSyncSummary

    func addStaticRelayPeer(_ endpoint: String) throws -> BrowserSyncSummary

    func clearResolverCache() throws -> BrowserSyncSummary

    func resetHeadersFromPeers() throws -> BrowserSyncSummary

    func proofDetails(for hostOrURL: String) throws -> BrowserProofDetails

    func close()
}

extension BrowserRuntime {
    func addStaticRelayPeer(_ endpoint: String) throws -> BrowserSyncSummary {
        throw BrowserCoreError.runtimeUnavailable("static relay peer management is unavailable")
    }

    func resetHeadersFromPeers() throws -> BrowserSyncSummary {
        throw BrowserCoreError.runtimeUnavailable("header reset is unavailable")
    }
}
