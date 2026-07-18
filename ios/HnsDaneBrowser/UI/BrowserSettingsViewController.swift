import UIKit

@MainActor
protocol BrowserSettingsViewControllerDelegate: AnyObject {
    func browserSettingsViewController(
        _ controller: BrowserSettingsViewController,
        didRequest action: BrowserSettingsViewController.Action
    )
}

/// Native iOS settings UI that mirrors the canonical Android section and row
/// hierarchy. Platform-specific actions are implemented with native iOS UI.
@MainActor
final class BrowserSettingsViewController: UITableViewController {
    enum Action: Equatable {
        case setHomepage(String)
        case resetHomepage
        case showCookies
        case showHistory
        case showDownloads
        case setTheme(BrowserThemeMode)
        case openLanguageSettings
        case setHandshakeNetwork(BrowserHandshakeNetwork)
        case addStaticRelayPeer(String)
        case applyRuntimePolicy(BrowserRuntimePolicy)
        case clearResolverCache
        case runHNSSync
        case resetHeadersFromPeers
        case showHNSDomainSetup
        case showResolverTrace
        case showHNSProofDetails
        case showTLSADANEInspector
        case showDiagnostics
        case showGateway
        case showLegal
        case showPrivacyPolicy
        case showSourceCode
    }

    enum Section: Int, CaseIterable {
        case startPage
        case privacyAndData
        case appearance
        case language
        case hnsResolution
        case diagnosticsAndTools
        case aboutLegalAndSupport

        var title: String {
            switch self {
            case .startPage: "Start page"
            case .privacyAndData: "Privacy and data"
            case .appearance: "Appearance"
            case .language: "Language"
            case .hnsResolution: "HNS resolution"
            case .diagnosticsAndTools: "Diagnostics and tools"
            case .aboutLegalAndSupport: "About, legal, and support"
            }
        }

        var accessibilityIdentifier: String {
            switch self {
            case .startPage: "settings.section.start-page"
            case .privacyAndData: "settings.section.privacy-and-data"
            case .appearance: "settings.section.appearance"
            case .language: "settings.section.language"
            case .hnsResolution: "settings.section.hns-resolution"
            case .diagnosticsAndTools: "settings.section.diagnostics-and-tools"
            case .aboutLegalAndSupport: "settings.section.about-legal-and-support"
            }
        }
    }

    enum Row: Int, CaseIterable {
        case homepage
        case setCurrentPageAsHomepage
        case resetHomepage
        case cookies
        case history
        case downloads
        case theme
        case appLanguage
        case handshakeNetwork
        case strictHNSMode
        case statelessDANECertificates
        case experimentalP2PDNSRelay
        case addHNSRelayPeer
        case legacyHNSDoHCompatibility
        case compatibilityDoHResolver
        case clearResolverCache
        case hnsSync
        case hnsDomainSetup
        case resolverTrace
        case hnsProofDetails
        case tlsaDANEInspector
        case diagnostics
        case gateway
        case build
        case legal
        case privacyPolicy
        case sourceCode

        var title: String {
            switch self {
            case .homepage: "Homepage"
            case .setCurrentPageAsHomepage: "Set current page as homepage"
            case .resetHomepage: "Reset homepage"
            case .cookies: "Cookies"
            case .history: "History"
            case .downloads: "Downloads"
            case .theme: "Theme"
            case .appLanguage: "App language"
            case .handshakeNetwork: "Handshake network"
            case .strictHNSMode: "Strict HNS mode"
            case .statelessDANECertificates: "Experimental stateless DANE certificates"
            case .experimentalP2PDNSRelay: "Experimental P2P DNS relay"
            case .addHNSRelayPeer: "Add HNS relay peer"
            case .legacyHNSDoHCompatibility: "Legacy HNS DoH compatibility"
            case .compatibilityDoHResolver: "Compatibility DoH resolver"
            case .clearResolverCache: "Clear resolver cache"
            case .hnsSync: "HNS sync"
            case .hnsDomainSetup: "HNS domain setup"
            case .resolverTrace: "Resolver trace"
            case .hnsProofDetails: "HNS proof details"
            case .tlsaDANEInspector: "TLSA / DANE inspector"
            case .diagnostics: "Diagnostics"
            case .gateway: "Gateway"
            case .build: "Build"
            case .legal: "Legal"
            case .privacyPolicy: "Privacy policy"
            case .sourceCode: "Source code"
            }
        }

        var accessibilityIdentifier: String {
            switch self {
            case .homepage: "settings.start-page.homepage"
            case .setCurrentPageAsHomepage: "settings.start-page.set-current-page"
            case .resetHomepage: "settings.start-page.reset-homepage"
            case .cookies: "settings.privacy-and-data.cookies"
            case .history: "settings.privacy-and-data.history"
            case .downloads: "settings.privacy-and-data.downloads"
            case .theme: "settings.appearance.theme"
            case .appLanguage: "settings.language.app-language"
            case .handshakeNetwork: "settings.hns-resolution.handshake-network"
            case .strictHNSMode: "settings.hns-resolution.strict-hns-mode"
            case .statelessDANECertificates:
                "settings.hns-resolution.stateless-dane-certificates"
            case .experimentalP2PDNSRelay:
                "settings.hns-resolution.experimental-p2p-dns-relay"
            case .addHNSRelayPeer: "settings.hns-resolution.add-hns-relay-peer"
            case .legacyHNSDoHCompatibility:
                "settings.hns-resolution.legacy-hns-doh-compatibility"
            case .compatibilityDoHResolver:
                "settings.hns-resolution.compatibility-doh-resolver"
            case .clearResolverCache: "settings.hns-resolution.clear-resolver-cache"
            case .hnsSync: "settings.hns-resolution.hns-sync"
            case .hnsDomainSetup: "settings.diagnostics-and-tools.hns-domain-setup"
            case .resolverTrace: "settings.diagnostics-and-tools.resolver-trace"
            case .hnsProofDetails: "browser-settings.proof-details"
            case .tlsaDANEInspector: "settings.diagnostics-and-tools.tlsa-dane-inspector"
            case .diagnostics: "settings.diagnostics-and-tools.diagnostics"
            case .gateway: "settings.diagnostics-and-tools.gateway"
            case .build: "settings.about-legal-and-support.build"
            case .legal: "settings.about-legal-and-support.legal"
            case .privacyPolicy: "settings.about-legal-and-support.privacy-policy"
            case .sourceCode: "settings.about-legal-and-support.source-code"
            }
        }

        var isRuntimeAction: Bool {
            switch self {
            case .handshakeNetwork,
                 .strictHNSMode,
                 .statelessDANECertificates,
                 .experimentalP2PDNSRelay,
                 .addHNSRelayPeer,
                 .legacyHNSDoHCompatibility,
                 .compatibilityDoHResolver,
                 .clearResolverCache,
                 .hnsDomainSetup,
                 .resolverTrace,
                 .hnsProofDetails,
                 .tlsaDANEInspector:
                true
            case .homepage,
                 .setCurrentPageAsHomepage,
                 .resetHomepage,
                 .cookies,
                 .history,
                 .downloads,
                 .theme,
                 .appLanguage,
                 .hnsSync,
                 .diagnostics,
                 .gateway,
                 .build,
                 .legal,
                 .privacyPolicy,
                 .sourceCode:
                false
            }
        }

        var isToggle: Bool {
            switch self {
            case .strictHNSMode,
                 .statelessDANECertificates,
                 .experimentalP2PDNSRelay,
                 .legacyHNSDoHCompatibility:
                true
            default:
                false
            }
        }
    }

    static let privacyPolicyURL = "https://denuoweb.com/work/hns-dane-browser/privacy"
    static let sourceCodeURL = "https://github.com/Denuo-Web/hns-dane-browser"
    static let defaultDoHResolverURL = "https://zorro.hnsdoh.com/dns-query"

    weak var delegate: BrowserSettingsViewControllerDelegate?

    private var policy: BrowserRuntimePolicy
    private var runtimeControlsAreAvailable: Bool
    private var isOperationInFlight: Bool
    private var syncSummary: BrowserSyncSummary
    private var resolverCacheSummary: String
    private var currentPageURL: String?
    private var homepage: String
    private var historyCount: Int
    private var downloadCount: Int
    private var themeMode: BrowserThemeMode
    private var handshakeNetwork: BrowserHandshakeNetwork
    private var relayPeerSummary =
        "Add a known relay-capable peer when discovery has not found one. Existing peers remain available."
    private weak var hnsSyncViewController: HNSSyncViewController?

    init(
        policy: BrowserRuntimePolicy,
        runtimeControlsAreAvailable: Bool,
        isOperationInFlight: Bool = false,
        syncSummary: BrowserSyncSummary = .unavailable,
        resolverCacheSummary: String = "Ready to clear cached resolver values.",
        currentPageURL: String? = nil,
        homepage: String = BrowserSettingsPreferences.defaultHomepage,
        historyCount: Int = 0,
        downloadCount: Int = 0,
        themeMode: BrowserThemeMode = .system,
        handshakeNetwork: BrowserHandshakeNetwork = .mainnet
    ) {
        self.policy = policy
        self.runtimeControlsAreAvailable = runtimeControlsAreAvailable
        self.isOperationInFlight = isOperationInFlight
        self.syncSummary = syncSummary
        self.resolverCacheSummary = resolverCacheSummary
        self.currentPageURL = Self.supportedCurrentPageURL(currentPageURL)
        self.homepage = homepage
        self.historyCount = historyCount
        self.downloadCount = downloadCount
        self.themeMode = themeMode
        self.handshakeNetwork = handshakeNetwork
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Settings"
        view.backgroundColor = .systemGroupedBackground
        tableView.accessibilityIdentifier = "settings.table"
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 76
        tableView.sectionHeaderHeight = UITableView.automaticDimension
        tableView.estimatedSectionHeaderHeight = 44
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .close,
            target: self,
            action: #selector(closeSettings)
        )
        navigationItem.rightBarButtonItem?.accessibilityLabel = "Close settings"
        navigationItem.rightBarButtonItem?.accessibilityIdentifier = "settings.close"
    }

    /// Refreshes displayed state after the browser completes an asynchronous
    /// settings action or the current page changes.
    func update(
        policy: BrowserRuntimePolicy,
        runtimeControlsAreAvailable: Bool,
        isOperationInFlight: Bool,
        syncSummary: BrowserSyncSummary = .unavailable,
        resolverCacheSummary: String? = nil,
        currentPageURL: String? = nil,
        homepage: String? = nil,
        historyCount: Int? = nil,
        downloadCount: Int? = nil,
        themeMode: BrowserThemeMode? = nil,
        handshakeNetwork: BrowserHandshakeNetwork? = nil
    ) {
        self.policy = policy
        self.runtimeControlsAreAvailable = runtimeControlsAreAvailable
        self.isOperationInFlight = isOperationInFlight
        self.syncSummary = syncSummary
        if let resolverCacheSummary {
            self.resolverCacheSummary = resolverCacheSummary
        }
        self.currentPageURL = Self.supportedCurrentPageURL(currentPageURL)
        if let homepage { self.homepage = homepage }
        if let historyCount { self.historyCount = historyCount }
        if let downloadCount { self.downloadCount = downloadCount }
        if let themeMode { self.themeMode = themeMode }
        if let handshakeNetwork { self.handshakeNetwork = handshakeNetwork }
        guard isViewLoaded else { return }
        tableView.reloadData()
        hnsSyncViewController?.update(
            summary: syncSummary,
            runtimeControlsAreAvailable: runtimeControlsAreAvailable,
            isOperationInFlight: isOperationInFlight
        )
    }

    func updateRelayPeerSummary(_ summary: String) {
        relayPeerSummary = summary
        guard isViewLoaded else { return }
        tableView.reloadData()
    }

    static func rows(in section: Section) -> [Row] {
        switch section {
        case .startPage:
            [.homepage, .setCurrentPageAsHomepage, .resetHomepage]
        case .privacyAndData:
            [.cookies, .history, .downloads]
        case .appearance:
            [.theme]
        case .language:
            [.appLanguage]
        case .hnsResolution:
            [
                .handshakeNetwork,
                .strictHNSMode,
                .statelessDANECertificates,
                .experimentalP2PDNSRelay,
                .addHNSRelayPeer,
                .legacyHNSDoHCompatibility,
                .compatibilityDoHResolver,
                .clearResolverCache,
                .hnsSync,
            ]
        case .diagnosticsAndTools:
            [
                .hnsDomainSetup,
                .resolverTrace,
                .hnsProofDetails,
                .tlsaDANEInspector,
                .diagnostics,
                .gateway,
            ]
        case .aboutLegalAndSupport:
            // Apple requires developer tipping in App Store apps to use In-App Purchase.
            [.build, .legal, .privacyPolicy, .sourceCode]
        }
    }

    private func displayedRows(in section: Section) -> [Row] {
        Self.rows(in: section).filter {
            $0 != .setCurrentPageAsHomepage || currentPageURL != nil
        }
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        Section.allCases.count
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        guard let section = Section(rawValue: section) else { return 0 }
        return displayedRows(in: section).count
    }

    override func tableView(
        _ tableView: UITableView,
        viewForHeaderInSection sectionIndex: Int
    ) -> UIView? {
        guard let section = Section(rawValue: sectionIndex) else { return nil }
        let header = UITableViewHeaderFooterView(reuseIdentifier: nil)
        var content = UIListContentConfiguration.groupedHeader()
        content.text = section.title
        content.textProperties.font = .preferredFont(forTextStyle: .headline)
        header.contentConfiguration = content
        header.accessibilityIdentifier = section.accessibilityIdentifier
        return header
    }

    override func tableView(
        _ tableView: UITableView,
        cellForRowAt indexPath: IndexPath
    ) -> UITableViewCell {
        guard let row = row(at: indexPath) else { return UITableViewCell() }
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.accessibilityIdentifier = row.accessibilityIdentifier
        cell.backgroundColor = .secondarySystemGroupedBackground

        var content = UIListContentConfiguration.subtitleCell()
        content.text = row.title
        content.secondaryText = summary(for: row)
        content.textProperties.font = .preferredFont(forTextStyle: .body)
        content.textProperties.numberOfLines = 0
        content.secondaryTextProperties.font = .preferredFont(forTextStyle: .footnote)
        content.secondaryTextProperties.color = .secondaryLabel
        content.secondaryTextProperties.numberOfLines = 0
        content.prefersSideBySideTextAndSecondaryText = false
        cell.contentConfiguration = content

        if row.isToggle {
            configureToggleCell(cell, row: row)
        } else {
            configureActionCell(cell, row: row)
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard let row = row(at: indexPath), !row.isToggle else { return }
        guard !row.isRuntimeAction || runtimeActionsAreEnabled else { return }

        switch row {
        case .homepage:
            presentHomepageConfiguration()
        case .setCurrentPageAsHomepage:
            guard let currentPageURL else { return }
            homepage = currentPageURL
            request(.setHomepage(currentPageURL))
            tableView.reloadData()
        case .resetHomepage:
            confirmResetHomepage()
        case .cookies:
            request(.showCookies)
        case .history:
            request(.showHistory)
        case .downloads:
            request(.showDownloads)
        case .theme:
            presentThemeConfiguration()
        case .appLanguage:
            request(.openLanguageSettings)
        case .handshakeNetwork:
            presentNetworkConfiguration()
        case .addHNSRelayPeer:
            presentRelayPeerConfiguration()
        case .compatibilityDoHResolver:
            presentDoHConfiguration()
        case .clearResolverCache:
            confirmClearResolverCache()
        case .hnsSync:
            showHNSSync()
        case .hnsDomainSetup:
            request(.showHNSDomainSetup, marksOperationInFlight: true)
        case .resolverTrace:
            request(.showResolverTrace, marksOperationInFlight: true)
        case .hnsProofDetails:
            request(.showHNSProofDetails, marksOperationInFlight: true)
        case .tlsaDANEInspector:
            request(.showTLSADANEInspector, marksOperationInFlight: true)
        case .diagnostics:
            request(.showDiagnostics)
        case .gateway:
            request(.showGateway)
        case .legal:
            request(.showLegal)
        case .privacyPolicy:
            request(.showPrivacyPolicy)
        case .sourceCode:
            request(.showSourceCode)
        case .build,
             .strictHNSMode,
             .statelessDANECertificates,
             .experimentalP2PDNSRelay,
             .legacyHNSDoHCompatibility:
            break
        }
    }

    private var runtimeActionsAreEnabled: Bool {
        runtimeControlsAreAvailable && !isOperationInFlight
    }

    private func row(at indexPath: IndexPath) -> Row? {
        guard let section = Section(rawValue: indexPath.section) else { return nil }
        let rows = displayedRows(in: section)
        guard rows.indices.contains(indexPath.row) else { return nil }
        return rows[indexPath.row]
    }

    private func summary(for row: Row) -> String {
        switch row {
        case .homepage:
            return homepage
        case .setCurrentPageAsHomepage:
            return currentPageURL ?? ""
        case .resetHomepage:
            return "Restore the default Denuo Web homepage."
        case .cookies:
            return "Manage cookies and website data used by this browser."
        case .history:
            return historyCount == 1 ? "1 saved page" : "\(historyCount) saved pages"
        case .downloads:
            return downloadCount == 1
                ? "1 app-queued record"
                : "\(downloadCount) app-queued records"
        case .theme:
            return themeMode.summary
        case .appLanguage:
            return "Uses your iOS system or per-app language setting."
        case .handshakeNetwork:
            return "\(handshakeNetwork.title). \(handshakeNetwork.summary)"
        case .strictHNSMode:
            if policy.resolutionMode == .strict {
                return "On. Delegated resolution failures fail closed."
            }
            return "Off. Compatibility fallback may be used after local or direct resolution fails."
        case .statelessDANECertificates:
            if policy.statelessDANECertificates {
                return "On. Certificate-carried HNS proof evidence may satisfy DANE when valid."
            }
            return "Off. HNS proof and TLSA evidence use the live resolver path."
        case .experimentalP2PDNSRelay:
            if policy.experimentalP2PDNSRelay {
                return "On by default. Delegated DNS may use relay-capable Handshake peers; DNSSEC validation remains local."
            }
            return "Off. Peer DNS relay messages are not used."
        case .addHNSRelayPeer:
            return relayPeerSummary
        case .legacyHNSDoHCompatibility:
            if policy.legacyHNSDoHCompatibility {
                return "On by default. The configured third-party HNS DoH path remains available as a compatibility fallback."
            }
            return "Off. The legacy third-party HNS DoH compatibility path is disabled independently of P2P relay."
        case .compatibilityDoHResolver:
            return policy.hnsDohResolver ?? Self.defaultDoHResolverURL
        case .clearResolverCache:
            return resolverCacheSummary
        case .hnsSync:
            return "View sync status and run a manual sync."
        case .hnsDomainSetup:
            return "Check records and delegation for an HNS domain."
        case .resolverTrace:
            return "Inspect resolution steps for a name."
        case .hnsProofDetails:
            return "Inspect local proof data for an HNS name."
        case .tlsaDANEInspector:
            return "Check TLSA records and DANE policy."
        case .diagnostics:
            return "Build, runtime, and native core details."
        case .gateway:
            return "Inspect recent structured browser gateway activity."
        case .build:
            return Self.buildLabel
        case .legal:
            return "Privacy policy, license, and user agreement."
        case .privacyPolicy:
            return Self.privacyPolicyURL
        case .sourceCode:
            return Self.sourceCodeURL
        }
    }

    private func configureToggleCell(_ cell: UITableViewCell, row: Row) {
        let toggle = UISwitch()
        toggle.tag = row.rawValue
        toggle.isOn = toggleValue(for: row)
        toggle.isEnabled = runtimeActionsAreEnabled
        toggle.accessibilityLabel = row.title
        toggle.accessibilityIdentifier = "\(row.accessibilityIdentifier).toggle"
        toggle.addTarget(self, action: #selector(runtimeToggleChanged(_:)), for: .valueChanged)
        cell.accessoryView = toggle
        cell.selectionStyle = .none
        cell.isUserInteractionEnabled = true
        applyEnabledAppearance(runtimeActionsAreEnabled, to: cell)
    }

    private func configureActionCell(_ cell: UITableViewCell, row: Row) {
        let enabled = !row.isRuntimeAction || runtimeActionsAreEnabled
        cell.isUserInteractionEnabled = enabled || row == .build
        applyEnabledAppearance(enabled || row == .build, to: cell)

        switch row {
        case .build:
            cell.selectionStyle = .none
        case .resetHomepage, .clearResolverCache:
            var content = cell.contentConfiguration as? UIListContentConfiguration
                ?? .subtitleCell()
            content.textProperties.color = enabled ? .systemRed : .tertiaryLabel
            cell.contentConfiguration = content
        default:
            break
        }

        if let actionTitle = actionTitle(for: row) {
            let actionLabel = UILabel()
            actionLabel.text = actionTitle
            actionLabel.font = .preferredFont(forTextStyle: .subheadline)
            actionLabel.adjustsFontForContentSizeCategory = true
            actionLabel.textColor = enabled
                ? (row == .clearResolverCache || row == .resetHomepage
                    ? .systemRed
                    : view.tintColor)
                : .tertiaryLabel
            actionLabel.accessibilityElementsHidden = true
            cell.accessoryView = actionLabel
        }
    }

    private func applyEnabledAppearance(_ enabled: Bool, to cell: UITableViewCell) {
        guard var content = cell.contentConfiguration as? UIListContentConfiguration else { return }
        content.textProperties.color = enabled ? .label : .tertiaryLabel
        content.secondaryTextProperties.color = enabled ? .secondaryLabel : .tertiaryLabel
        cell.contentConfiguration = content
    }

    private func actionTitle(for row: Row) -> String? {
        switch row {
        case .homepage, .compatibilityDoHResolver: "Edit"
        case .setCurrentPageAsHomepage: "Set"
        case .resetHomepage: "Reset"
        case .cookies: "Manage"
        case .history,
             .downloads,
             .hnsSync,
             .diagnostics,
             .gateway,
             .legal:
            "View"
        case .theme, .handshakeNetwork: "Change"
        case .addHNSRelayPeer: "Add"
        case .clearResolverCache: "Clear"
        case .appLanguage,
             .hnsDomainSetup,
             .resolverTrace,
             .hnsProofDetails,
             .tlsaDANEInspector,
             .privacyPolicy,
             .sourceCode:
            "Open"
        case .build,
             .strictHNSMode,
             .statelessDANECertificates,
             .experimentalP2PDNSRelay,
             .legacyHNSDoHCompatibility:
            nil
        }
    }

    private func toggleValue(for row: Row) -> Bool {
        switch row {
        case .strictHNSMode:
            policy.resolutionMode == .strict
        case .statelessDANECertificates:
            policy.statelessDANECertificates
        case .experimentalP2PDNSRelay:
            policy.experimentalP2PDNSRelay
        case .legacyHNSDoHCompatibility:
            policy.legacyHNSDoHCompatibility
        default:
            false
        }
    }

    @objc private func runtimeToggleChanged(_ sender: UISwitch) {
        guard runtimeActionsAreEnabled, let row = Row(rawValue: sender.tag) else {
            sender.setOn(!sender.isOn, animated: true)
            return
        }

        let updatedPolicy: BrowserRuntimePolicy
        switch row {
        case .strictHNSMode:
            updatedPolicy = policyByReplacingResolutionMode(
                sender.isOn ? .strict : .compatibility
            )
        case .statelessDANECertificates:
            updatedPolicy = policyByReplacingStatelessDANECertificates(sender.isOn)
        case .experimentalP2PDNSRelay:
            updatedPolicy = policyByReplacingExperimentalP2PDNSRelay(sender.isOn)
        case .legacyHNSDoHCompatibility:
            updatedPolicy = policyByReplacingLegacyHNSDoHCompatibility(sender.isOn)
        default:
            return
        }
        requestPolicyUpdate(updatedPolicy)
    }

    private static func supportedCurrentPageURL(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty,
              let components = URLComponents(string: value),
              ["http", "https"].contains(components.scheme?.lowercased() ?? ""),
              components.host?.isEmpty == false else {
            return nil
        }
        return value
    }

    private static func normalizedHomepage(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty,
              value.count <= 16 * 1024 else {
            return nil
        }
        if let supported = supportedCurrentPageURL(value) {
            return supported
        }
        guard !value.contains("://"),
              !value.contains(where: { $0.isWhitespace }),
              value.contains(".") || value.hasSuffix("/") else {
            return nil
        }
        return value
    }

    private func presentHomepageConfiguration() {
        let alert = UIAlertController(
            title: "Edit homepage",
            message: "Enter an http:// or https:// URL, or an HNS name such as example/ or www.example/.",
            preferredStyle: .alert
        )
        alert.addTextField { [homepage] field in
            field.text = homepage
            field.placeholder = "https://example.com/ or example/"
            field.keyboardType = .URL
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.clearButtonMode = .whileEditing
            field.accessibilityIdentifier = "settings.start-page.homepage.field"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Save", style: .default) { [weak self, weak alert] _ in
            guard let self else { return }
            guard let normalized = Self.normalizedHomepage(alert?.textFields?.first?.text) else {
                self.presentValidationError(
                    title: "Invalid homepage",
                    message: "Enter an HTTP(S) URL or HNS name."
                )
                return
            }
            self.homepage = normalized
            self.request(.setHomepage(normalized))
            self.tableView.reloadData()
        })
        present(alert, animated: true)
    }

    private func confirmResetHomepage() {
        let alert = UIAlertController(
            title: "Reset homepage?",
            message: "This restores the default Denuo Web homepage.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Reset", style: .destructive) { [weak self] _ in
            guard let self else { return }
            self.homepage = BrowserSettingsPreferences.defaultHomepage
            self.request(.resetHomepage)
            self.tableView.reloadData()
        })
        present(alert, animated: true)
    }

    private func presentThemeConfiguration() {
        let alert = UIAlertController(title: "Theme", message: nil, preferredStyle: .alert)
        BrowserThemeMode.allCases.forEach { mode in
            let selected = mode == themeMode ? "✓ " : ""
            alert.addAction(UIAlertAction(title: selected + mode.title, style: .default) {
                [weak self] _ in
                guard let self else { return }
                self.themeMode = mode
                self.request(.setTheme(mode))
                self.tableView.reloadData()
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }

    private func presentNetworkConfiguration() {
        let alert = UIAlertController(
            title: "Handshake network",
            message: "Changing networks restarts the secure runtime with separate network data.",
            preferredStyle: .alert
        )
        BrowserHandshakeNetwork.allCases.forEach { network in
            let selected = network == handshakeNetwork ? "✓ " : ""
            alert.addAction(UIAlertAction(
                title: selected + "\(network.title) — \(network.summary)",
                style: .default
            ) { [weak self] _ in
                guard let self, network != self.handshakeNetwork else { return }
                self.handshakeNetwork = network
                self.resolverCacheSummary =
                    "Ready to clear cached resolver values for \(network.title)."
                self.request(.setHandshakeNetwork(network), marksOperationInFlight: true)
            })
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(alert, animated: true)
    }

    private func presentRelayPeerConfiguration() {
        let port: Int
        switch handshakeNetwork {
        case .mainnet: port = 12_038
        case .testnet: port = 13_038
        case .regtest: port = 14_038
        }
        let alert = UIAlertController(
            title: "Add HNS relay peer",
            message: "Enter a \(handshakeNetwork.title) Handshake peer as IPv4:port or [IPv6]:port. The app verifies its network handshake and live DNS-relay capability before saving it. Hostnames are not accepted.",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "IPv4:\(port) or [IPv6]:\(port)"
            field.keyboardType = .numbersAndPunctuation
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.accessibilityIdentifier = "settings.hns-resolution.add-hns-relay-peer.field"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Add", style: .default) { [weak self, weak alert] _ in
            guard let self else { return }
            let endpoint = alert?.textFields?.first?.text?.trimmingCharacters(
                in: .whitespacesAndNewlines
            ) ?? ""
            guard !endpoint.isEmpty,
                  endpoint.count <= 320,
                  !endpoint.contains(where: { $0.isWhitespace }),
                  endpoint.contains(":") else {
                self.presentValidationError(
                    title: "Invalid relay peer",
                    message: "Enter a valid IPv4:port or [IPv6]:port endpoint."
                )
                return
            }
            self.relayPeerSummary = "Verifying a relay-capable \(self.handshakeNetwork.title) peer…"
            self.request(.addStaticRelayPeer(endpoint), marksOperationInFlight: true)
        })
        present(alert, animated: true)
    }

    private func presentValidationError(title: String, message: String) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }

    private func presentDoHConfiguration() {
        let alert = UIAlertController(
            title: "Edit DoH resolver",
            message: "Enter an HTTPS DNS-over-HTTPS endpoint. Leave blank to use the default.",
            preferredStyle: .alert
        )
        alert.addTextField { [policy = self.policy] textField in
            textField.text = policy.hnsDohResolver ?? Self.defaultDoHResolverURL
            textField.placeholder = "https://resolver.example/dns-query"
            textField.keyboardType = .URL
            textField.autocapitalizationType = .none
            textField.autocorrectionType = .no
            textField.clearButtonMode = .whileEditing
            textField.accessibilityIdentifier =
                "settings.hns-resolution.compatibility-doh-resolver.field"
        }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Reset", style: .default) { [weak self] _ in
            guard let self else { return }
            self.requestPolicyUpdate(self.policyByReplacingDoHResolver(nil))
        })
        alert.addAction(UIAlertAction(title: "Save", style: .default) { [weak self, weak alert] _ in
            guard let self else { return }
            self.requestPolicyUpdate(
                self.policyByReplacingDoHResolver(alert?.textFields?.first?.text)
            )
        })
        present(alert, animated: true)
    }

    private func confirmClearResolverCache() {
        let alert = UIAlertController(
            title: "Clear resolver cache?",
            message: "The app will keep synced \(handshakeNetwork.title) headers and peers, but cached HNS resource values for this network will be removed.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Clear", style: .destructive) { [weak self] _ in
            self?.request(.clearResolverCache, marksOperationInFlight: true)
        })
        present(alert, animated: true)
    }

    private func showHNSSync() {
        let controller = HNSSyncViewController(
            summary: syncSummary,
            runtimeControlsAreAvailable: runtimeControlsAreAvailable,
            isOperationInFlight: isOperationInFlight
        )
        controller.onRunSync = { [weak self] in
            self?.request(.runHNSSync, marksOperationInFlight: true)
        }
        controller.onResetHeaders = { [weak self] in
            self?.request(.resetHeadersFromPeers, marksOperationInFlight: true)
        }
        hnsSyncViewController = controller
        navigationController?.pushViewController(controller, animated: true)
    }

    private func requestPolicyUpdate(_ updatedPolicy: BrowserRuntimePolicy) {
        guard updatedPolicy != policy else {
            tableView.reloadData()
            return
        }
        policy = updatedPolicy
        request(.applyRuntimePolicy(updatedPolicy), marksOperationInFlight: true)
    }

    private func request(_ action: Action, marksOperationInFlight: Bool = false) {
        if marksOperationInFlight {
            isOperationInFlight = true
            tableView.reloadData()
            hnsSyncViewController?.update(
                summary: syncSummary,
                runtimeControlsAreAvailable: runtimeControlsAreAvailable,
                isOperationInFlight: true
            )
        }
        delegate?.browserSettingsViewController(self, didRequest: action)
    }

    private func policyByReplacingResolutionMode(
        _ mode: BrowserResolutionMode
    ) -> BrowserRuntimePolicy {
        BrowserRuntimePolicy(
            resolutionMode: mode,
            hnsDohResolver: policy.hnsDohResolver,
            statelessDANECertificates: policy.statelessDANECertificates,
            experimentalP2PDNSRelay: policy.experimentalP2PDNSRelay,
            legacyHNSDoHCompatibility: policy.legacyHNSDoHCompatibility
        )
    }

    private func policyByReplacingDoHResolver(_ resolver: String?) -> BrowserRuntimePolicy {
        BrowserRuntimePolicy(
            resolutionMode: policy.resolutionMode,
            hnsDohResolver: resolver,
            statelessDANECertificates: policy.statelessDANECertificates,
            experimentalP2PDNSRelay: policy.experimentalP2PDNSRelay,
            legacyHNSDoHCompatibility: policy.legacyHNSDoHCompatibility
        )
    }

    private func policyByReplacingStatelessDANECertificates(
        _ enabled: Bool
    ) -> BrowserRuntimePolicy {
        BrowserRuntimePolicy(
            resolutionMode: policy.resolutionMode,
            hnsDohResolver: policy.hnsDohResolver,
            statelessDANECertificates: enabled,
            experimentalP2PDNSRelay: policy.experimentalP2PDNSRelay,
            legacyHNSDoHCompatibility: policy.legacyHNSDoHCompatibility
        )
    }

    private func policyByReplacingExperimentalP2PDNSRelay(
        _ enabled: Bool
    ) -> BrowserRuntimePolicy {
        BrowserRuntimePolicy(
            resolutionMode: policy.resolutionMode,
            hnsDohResolver: policy.hnsDohResolver,
            statelessDANECertificates: policy.statelessDANECertificates,
            experimentalP2PDNSRelay: enabled,
            legacyHNSDoHCompatibility: policy.legacyHNSDoHCompatibility
        )
    }

    private func policyByReplacingLegacyHNSDoHCompatibility(
        _ enabled: Bool
    ) -> BrowserRuntimePolicy {
        BrowserRuntimePolicy(
            resolutionMode: policy.resolutionMode,
            hnsDohResolver: policy.hnsDohResolver,
            statelessDANECertificates: policy.statelessDANECertificates,
            experimentalP2PDNSRelay: policy.experimentalP2PDNSRelay,
            legacyHNSDoHCompatibility: enabled
        )
    }

    private static var buildLabel: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")
            as? String ?? "Unknown"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion")
            as? String ?? "Unknown"
        return "release \(version) (\(build))"
    }

    static var buildLabelForDiagnostics: String { buildLabel }

    @objc private func closeSettings() {
        dismiss(animated: true)
    }
}

@MainActor
final class HNSSyncViewController: UITableViewController {
    enum Row: Int, CaseIterable {
        case syncStatus
        case runSyncNow
        case resyncHeadersFromPeers
    }

    var onRunSync: (() -> Void)?
    var onResetHeaders: (() -> Void)?

    private var summary: BrowserSyncSummary
    private var runtimeControlsAreAvailable: Bool
    private var isOperationInFlight: Bool

    init(
        summary: BrowserSyncSummary,
        runtimeControlsAreAvailable: Bool,
        isOperationInFlight: Bool
    ) {
        self.summary = summary
        self.runtimeControlsAreAvailable = runtimeControlsAreAvailable
        self.isOperationInFlight = isOperationInFlight
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "HNS Sync"
        view.backgroundColor = .systemGroupedBackground
        tableView.accessibilityIdentifier = "hns-sync.table"
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 96
    }

    func update(
        summary: BrowserSyncSummary,
        runtimeControlsAreAvailable: Bool,
        isOperationInFlight: Bool
    ) {
        self.summary = summary
        self.runtimeControlsAreAvailable = runtimeControlsAreAvailable
        self.isOperationInFlight = isOperationInFlight
        guard isViewLoaded else { return }
        tableView.reloadData()
    }

    override func numberOfSections(in tableView: UITableView) -> Int { 1 }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        Row.allCases.count
    }

    override func tableView(
        _ tableView: UITableView,
        titleForHeaderInSection section: Int
    ) -> String? {
        "HNS sync"
    }

    override func tableView(
        _ tableView: UITableView,
        cellForRowAt indexPath: IndexPath
    ) -> UITableViewCell {
        guard let row = Row(rawValue: indexPath.row) else { return UITableViewCell() }
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.backgroundColor = .secondarySystemGroupedBackground

        var content = UIListContentConfiguration.subtitleCell()
        content.textProperties.font = .preferredFont(forTextStyle: .body)
        content.secondaryTextProperties.font = .preferredFont(forTextStyle: .footnote)
        content.secondaryTextProperties.color = .secondaryLabel
        content.secondaryTextProperties.numberOfLines = 0
        content.prefersSideBySideTextAndSecondaryText = false

        switch row {
        case .syncStatus:
            content.text = "Sync status"
            content.secondaryText = Self.statusText(
                summary: summary,
                isOperationInFlight: isOperationInFlight
            )
            cell.accessibilityIdentifier = "hns-sync.status"
            cell.selectionStyle = .none
            if isOperationInFlight {
                let spinner = UIActivityIndicatorView(style: .medium)
                spinner.startAnimating()
                spinner.accessibilityLabel = "Sync running"
                cell.accessoryView = spinner
            }
        case .runSyncNow:
            content.text = "Run sync now"
            content.secondaryText =
                "Start a foreground HNS sync and watch the status update here."
            cell.accessibilityIdentifier = "hns-sync.run-now"
            let enabled = runtimeControlsAreAvailable && !isOperationInFlight
            content.textProperties.color = enabled ? .label : .tertiaryLabel
            content.secondaryTextProperties.color = enabled ? .secondaryLabel : .tertiaryLabel
            cell.isUserInteractionEnabled = enabled
            let actionLabel = UILabel()
            actionLabel.text = "Run"
            actionLabel.font = .preferredFont(forTextStyle: .subheadline)
            actionLabel.adjustsFontForContentSizeCategory = true
            actionLabel.textColor = enabled ? view.tintColor : .tertiaryLabel
            actionLabel.accessibilityElementsHidden = true
            cell.accessoryView = actionLabel
        case .resyncHeadersFromPeers:
            content.text = "Resync headers from peers"
            content.secondaryText =
                "Reset local headers and cached resolver values, then sync again from peers."
            cell.accessibilityIdentifier = "hns-sync.resync-headers"
            let enabled = runtimeControlsAreAvailable && !isOperationInFlight
            content.textProperties.color = enabled ? .systemRed : .tertiaryLabel
            content.secondaryTextProperties.color = enabled ? .secondaryLabel : .tertiaryLabel
            cell.isUserInteractionEnabled = enabled
            let actionLabel = UILabel()
            actionLabel.text = "Reset"
            actionLabel.font = .preferredFont(forTextStyle: .subheadline)
            actionLabel.adjustsFontForContentSizeCategory = true
            actionLabel.textColor = enabled ? .systemRed : .tertiaryLabel
            actionLabel.accessibilityElementsHidden = true
            cell.accessoryView = actionLabel
        }
        cell.contentConfiguration = content
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard let row = Row(rawValue: indexPath.row),
              runtimeControlsAreAvailable,
              !isOperationInFlight else {
            return
        }
        switch row {
        case .syncStatus:
            return
        case .runSyncNow:
            isOperationInFlight = true
            tableView.reloadData()
            onRunSync?()
        case .resyncHeadersFromPeers:
            confirmHeaderReset()
        }
    }

    private func confirmHeaderReset() {
        let alert = UIAlertController(
            title: "Resync headers from peers?",
            message: "This removes local headers and cached resolver values, then starts syncing again from block 0.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Reset", style: .destructive) { [weak self] _ in
            guard let self else { return }
            self.isOperationInFlight = true
            self.tableView.reloadData()
            self.onResetHeaders?()
        })
        present(alert, animated: true)
    }

    static func statusText(
        summary: BrowserSyncSummary,
        isOperationInFlight: Bool
    ) -> String {
        var lines = [isOperationInFlight ? "Running…" : summary.headline]
        if !summary.detail.isEmpty {
            lines.append(summary.detail)
        }
        if let network = summary.network, !network.isEmpty {
            lines.append("Network: \(network)")
        }
        if summary.peerCount > 0 || summary.peerGroups > 0 {
            lines.append("Peers: \(summary.peerCount) in \(summary.peerGroups) groups")
        }
        if summary.resourceCacheEntries > 0 || summary.resourceCacheBytes > 0 {
            lines.append(
                "Resolver cache: \(summary.resourceCacheEntries) entries, "
                    + "\(summary.resourceCacheBytes) bytes"
            )
        }
        return lines.joined(separator: "\n")
    }
}
