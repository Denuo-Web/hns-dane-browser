import UIKit
import WebKit

@MainActor
final class BrowserViewController: UIViewController {
    private let process: BrowserProcess

    private let backButton = UIButton(type: .system)
    private let forwardButton = UIButton(type: .system)
    private let reloadButton = UIButton(type: .system)
    private let shareButton = UIButton(type: .system)
    private let addressField = UITextField()
    private let securityLabel = UILabel()
    private let syncLabel = UILabel()
    private let progressView = UIProgressView(progressViewStyle: .bar)
    private let webContainer = UIView()
    private let placeholderLabel = UILabel()

    private var coordinator: BrowserProxyCoordinator?
    private var progressObservation: NSKeyValueObservation?
    private var pendingExternalAddress: String?
    private var isForeground = false
    private var isPreparing = false
    private var isLoading = false

    init(process: BrowserProcess) {
        self.process = process
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) is unavailable")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        configureUI()
        prepareRuntime()
    }

    func resumeBrowsing() {
        isForeground = true
        coordinator?.resume()
    }

    func suspendBrowsing() {
        isForeground = false
        coordinator?.suspend()
        placeholderLabel.text = "Secure browsing paused"
    }

    func destroyBrowsing() {
        coordinator?.destroy()
        coordinator = nil
        progressObservation = nil
    }

    func openExternalURL(_ url: URL) {
        guard url.isFileURL == false else {
            showError(BrowserCoreError.unsupportedAddress)
            return
        }
        if let coordinator {
            coordinator.navigate(rawValue: url.absoluteString)
        } else {
            pendingExternalAddress = url.absoluteString
        }
    }

    private func configureUI() {
        view.backgroundColor = .systemBackground

        configureButton(backButton, symbol: "chevron.backward", label: "Back", action: #selector(goBack))
        configureButton(forwardButton, symbol: "chevron.forward", label: "Forward", action: #selector(goForward))
        configureButton(reloadButton, symbol: "arrow.clockwise", label: "Reload", action: #selector(reloadOrStop))
        configureButton(shareButton, symbol: "square.and.arrow.up", label: "Share", action: #selector(sharePage))
        backButton.isEnabled = false
        forwardButton.isEnabled = false
        shareButton.isEnabled = false

        addressField.borderStyle = .roundedRect
        addressField.clearButtonMode = .whileEditing
        addressField.keyboardType = .URL
        addressField.returnKeyType = .go
        addressField.autocapitalizationType = .none
        addressField.autocorrectionType = .no
        addressField.spellCheckingType = .no
        addressField.placeholder = "Enter a web or Handshake address"
        addressField.accessibilityLabel = "Address"
        addressField.delegate = self

        securityLabel.font = .preferredFont(forTextStyle: .caption1)
        securityLabel.adjustsFontForContentSizeCategory = true
        securityLabel.textColor = .secondaryLabel
        securityLabel.numberOfLines = 1
        securityLabel.text = "Security pending"

        syncLabel.font = .preferredFont(forTextStyle: .caption2)
        syncLabel.adjustsFontForContentSizeCategory = true
        syncLabel.textColor = .tertiaryLabel
        syncLabel.numberOfLines = 1
        syncLabel.textAlignment = .right
        syncLabel.text = "Preparing runtime"

        placeholderLabel.translatesAutoresizingMaskIntoConstraints = false
        placeholderLabel.font = .preferredFont(forTextStyle: .title3)
        placeholderLabel.textColor = .secondaryLabel
        placeholderLabel.textAlignment = .center
        placeholderLabel.numberOfLines = 0
        placeholderLabel.text = "Preparing secure browsing…"

        let addressRow = UIStackView(arrangedSubviews: [backButton, forwardButton, addressField, reloadButton, shareButton])
        addressRow.axis = .horizontal
        addressRow.alignment = .center
        addressRow.spacing = 8
        backButton.widthAnchor.constraint(equalToConstant: 36).isActive = true
        forwardButton.widthAnchor.constraint(equalToConstant: 36).isActive = true
        reloadButton.widthAnchor.constraint(equalToConstant: 36).isActive = true
        shareButton.widthAnchor.constraint(equalToConstant: 36).isActive = true

        let statusRow = UIStackView(arrangedSubviews: [securityLabel, syncLabel])
        statusRow.axis = .horizontal
        statusRow.alignment = .firstBaseline
        statusRow.distribution = .fill
        statusRow.spacing = 8
        securityLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        syncLabel.setContentCompressionResistancePriority(.required, for: .horizontal)

        let chrome = UIStackView(arrangedSubviews: [addressRow, statusRow, progressView])
        chrome.axis = .vertical
        chrome.spacing = 6
        chrome.isLayoutMarginsRelativeArrangement = true
        chrome.directionalLayoutMargins = NSDirectionalEdgeInsets(top: 8, leading: 10, bottom: 6, trailing: 10)

        webContainer.backgroundColor = .secondarySystemBackground
        webContainer.addSubview(placeholderLabel)
        NSLayoutConstraint.activate([
            placeholderLabel.centerXAnchor.constraint(equalTo: webContainer.centerXAnchor),
            placeholderLabel.centerYAnchor.constraint(equalTo: webContainer.centerYAnchor),
            placeholderLabel.leadingAnchor.constraint(greaterThanOrEqualTo: webContainer.leadingAnchor, constant: 24),
            placeholderLabel.trailingAnchor.constraint(lessThanOrEqualTo: webContainer.trailingAnchor, constant: -24),
        ])

        let root = UIStackView(arrangedSubviews: [chrome, webContainer])
        root.translatesAutoresizingMaskIntoConstraints = false
        root.axis = .vertical
        root.spacing = 0
        view.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor),
            root.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor),
            root.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            root.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    private func configureButton(
        _ button: UIButton,
        symbol: String,
        label: String,
        action: Selector
    ) {
        button.setImage(UIImage(systemName: symbol), for: .normal)
        button.accessibilityLabel = label
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    private func prepareRuntime() {
        guard !isPreparing, coordinator == nil else { return }
        isPreparing = true
        placeholderLabel.text = "Preparing secure browsing…"
        process.prepare { [weak self] result in
            guard let self else { return }
            self.isPreparing = false
            switch result {
            case .success(let environment):
                let coordinator = BrowserProxyCoordinator(
                    runtime: environment.runtime,
                    profile: environment.profile
                )
                coordinator.delegate = self
                self.coordinator = coordinator
                self.placeholderLabel.text = "Enter an address to begin"
                self.syncLabel.text = environment.runtime.syncSummary().headline
                if self.isForeground {
                    coordinator.resume()
                }
                if let pending = self.pendingExternalAddress {
                    self.pendingExternalAddress = nil
                    coordinator.navigate(rawValue: pending)
                }
            case .failure(let error):
                self.placeholderLabel.text = "Secure runtime preparation failed"
                self.showPreparationError(error)
            }
        }
    }

    private func showPreparationError(_ error: Error) {
        guard presentedViewController == nil else { return }
        let alert = UIAlertController(
            title: "Unable to prepare secure browsing",
            message: error.localizedDescription,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Retry", style: .default) { [weak self] _ in
            self?.prepareRuntime()
        })
        present(alert, animated: true)
    }

    private func showError(_ error: Error) {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled { return }
        placeholderLabel.text = error.localizedDescription
        guard presentedViewController == nil else { return }
        let alert = UIAlertController(
            title: "Navigation failed",
            message: error.localizedDescription,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default))
        present(alert, animated: true)
    }

    @objc private func goBack() {
        coordinator?.goBack()
    }

    @objc private func goForward() {
        coordinator?.goForward()
    }

    @objc private func reloadOrStop() {
        if isLoading {
            coordinator?.stopLoading()
        } else {
            coordinator?.reload()
        }
    }

    @objc private func sharePage() {
        guard let url = coordinator?.currentShareURL else { return }
        presentShareSheet(items: [url], sourceView: shareButton)
    }

    private func presentShareSheet(items: [Any], sourceView: UIView) {
        let activity = UIActivityViewController(activityItems: items, applicationActivities: nil)
        if let popover = activity.popoverPresentationController {
            popover.sourceView = sourceView
            popover.sourceRect = sourceView.bounds
        }
        present(activity, animated: true)
    }
}

extension BrowserViewController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        guard let value = textField.text else { return false }
        coordinator?.navigate(rawValue: value)
        return true
    }
}

extension BrowserViewController: BrowserProxyCoordinatorDelegate {
    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, install webView: WKWebView) {
        progressObservation = webView.observe(\.estimatedProgress, options: [.initial, .new]) { [weak self] webView, _ in
            DispatchQueue.main.async {
                self?.progressView.progress = Float(webView.estimatedProgress)
            }
        }
        webView.translatesAutoresizingMaskIntoConstraints = false
        webContainer.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: webContainer.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: webContainer.trailingAnchor),
            webView.topAnchor.constraint(equalTo: webContainer.topAnchor),
            webView.bottomAnchor.constraint(equalTo: webContainer.bottomAnchor),
        ])
        placeholderLabel.isHidden = true
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, remove webView: WKWebView) {
        progressObservation = nil
        placeholderLabel.isHidden = false
        placeholderLabel.text = isForeground ? "Switching secure browsing context…" : "Secure browsing paused"
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didUpdateAddress address: String) {
        addressField.text = address
        shareButton.isEnabled = true
    }

    func proxyCoordinator(
        _ coordinator: BrowserProxyCoordinator,
        canGoBack: Bool,
        canGoForward: Bool,
        isLoading: Bool
    ) {
        backButton.isEnabled = canGoBack
        forwardButton.isEnabled = canGoForward
        self.isLoading = isLoading
        let symbol = isLoading ? "xmark" : "arrow.clockwise"
        reloadButton.setImage(UIImage(systemName: symbol), for: .normal)
        reloadButton.accessibilityLabel = isLoading ? "Stop" : "Reload"
        if !isLoading { progressView.progress = 0 }
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didUpdateSecurity summary: BrowserSecuritySummary) {
        let symbol: String
        let color: UIColor
        switch summary.level {
        case .pending:
            symbol = "hourglass"
            color = .secondaryLabel
        case .webPKI:
            symbol = "lock.fill"
            color = .systemBlue
        case .insecure:
            symbol = "lock.open.fill"
            color = .systemOrange
        case .handshakeDANE:
            symbol = "checkmark.shield.fill"
            color = .systemGreen
        case .handshakeFallback:
            symbol = "shield.lefthalf.filled"
            color = .systemOrange
        case .blocked:
            symbol = "xmark.shield.fill"
            color = .systemRed
        }
        let attachment = NSTextAttachment()
        attachment.image = UIImage(systemName: symbol)?.withTintColor(color)
        let value = NSMutableAttributedString(attachment: attachment)
        value.append(NSAttributedString(string: " \(summary.detail)"))
        securityLabel.attributedText = value
        securityLabel.accessibilityLabel = summary.detail
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didUpdateSync summary: BrowserSyncSummary) {
        syncLabel.text = summary.headline
        syncLabel.accessibilityLabel = "\(summary.headline). \(summary.detail)"
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didFail error: Error) {
        showError(error)
    }

    func proxyCoordinator(_ coordinator: BrowserProxyCoordinator, didFinishDownloadAt url: URL) {
        let alert = UIAlertController(
            title: "Download complete",
            message: url.lastPathComponent,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Share or Save to Files", style: .default) { [weak self] _ in
            guard let self else { return }
            self.presentShareSheet(items: [url], sourceView: self.shareButton)
        })
        alert.addAction(UIAlertAction(title: "Done", style: .cancel))
        present(alert, animated: true)
    }
}
