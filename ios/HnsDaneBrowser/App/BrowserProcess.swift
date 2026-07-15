import Foundation
import WebKit

@MainActor
final class BrowserProcess {
    struct Environment {
        let runtime: BrowserRuntime
        let profile: PersistentWebKitProfile
    }

    private enum State {
        case idle
        case preparing([((Result<Environment, Error>) -> Void)])
        case ready(Environment)
        case failed(Error)
        case closed
    }

    private let preparationQueue = DispatchQueue(
        label: "com.denuoweb.hnsdane.ios.runtime-preparation",
        qos: .userInitiated
    )
    private let runtimeFactory: (String) throws -> BrowserRuntime
    private let bootstrapper: HeaderSnapshotBootstrapper
    private var state: State = .idle

    init(
        runtimeFactory: @escaping (String) throws -> BrowserRuntime = { path in
            try RustBrowserRuntime(path)
        },
        bootstrapper: HeaderSnapshotBootstrapper = HeaderSnapshotBootstrapper()
    ) {
        self.runtimeFactory = runtimeFactory
        self.bootstrapper = bootstrapper
    }

    func prepare(completion: @escaping (Result<Environment, Error>) -> Void) {
        switch state {
        case .ready(let environment):
            completion(.success(environment))
            return
        case .failed:
            // A first-run bundle/I/O failure remains fail-closed, but a user-initiated retry may
            // recover from transient protected-storage or resource pressure failures.
            state = .preparing([completion])
        case .closed:
            completion(.failure(BrowserCoreError.runtimeUnavailable("process is closed")))
            return
        case .preparing(var callbacks):
            callbacks.append(completion)
            state = .preparing(callbacks)
            return
        case .idle:
            state = .preparing([completion])
        }

        do {
            let dataDirectory = try Self.makeDataDirectory()
            let runtimeFactory = runtimeFactory
            let bootstrapper = bootstrapper
            preparationQueue.async { [weak self] in
                let result: Result<BrowserRuntime, Error>
                do {
                    let runtime = try runtimeFactory(dataDirectory.path)
                    do {
                        try bootstrapper.installIfNeeded(into: runtime)
                        result = .success(runtime)
                    } catch {
                        runtime.close()
                        throw error
                    }
                } catch {
                    result = .failure(error)
                }

                DispatchQueue.main.async {
                    self?.finishPreparation(result)
                }
            }
        } catch {
            finishPreparation(.failure(error))
        }
    }

    func close() {
        let runtime: BrowserRuntime?
        switch state {
        case .ready(let environment):
            runtime = environment.runtime
        default:
            runtime = nil
        }
        state = .closed
        if let runtime {
            preparationQueue.async {
                runtime.close()
            }
        }
    }

    private func finishPreparation(_ result: Result<BrowserRuntime, Error>) {
        guard case .preparing(let callbacks) = state else {
            if case .success(let runtime) = result {
                preparationQueue.async { runtime.close() }
            }
            return
        }

        switch result {
        case .success(let runtime):
            let environment = Environment(
                runtime: runtime,
                profile: PersistentWebKitProfile()
            )
            state = .ready(environment)
            callbacks.forEach { $0(.success(environment)) }
            // Network synchronization is deliberately not a readiness gate. The exact bundled
            // snapshot is installed first; ICANN browsing and snapshot-backed HNS verification
            // can start while peer synchronization continues on the preparation queue.
            preparationQueue.async {
                runtime.syncOnce()
            }
        case .failure(let error):
            state = .failed(error)
            callbacks.forEach { $0(.failure(error)) }
        }
    }

    private static func makeDataDirectory() throws -> URL {
        let fileManager = FileManager.default
        guard let applicationSupport = fileManager.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else {
            throw BrowserCoreError.runtimeUnavailable("Application Support is unavailable")
        }
        let directory = applicationSupport
            .appendingPathComponent("HnsDaneBrowser", isDirectory: true)
        try fileManager.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        return directory
    }
}
