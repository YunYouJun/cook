import Foundation
import Observation
import UIKit

/// Holds the cross-app transaction only while Cook is running.
@MainActor @Observable
final class YunlefunAppAuthorization {
    enum Outcome {
        case authorized([String: String])
        case useWeb
    }

    typealias OpenApp = @MainActor (URL) async -> Bool

    private(set) var isWaiting = false
    @ObservationIgnored private let openApp: OpenApp
    @ObservationIgnored private var pending: (transaction: YunlefunSSO, continuation: CheckedContinuation<Outcome, Error>)?
    @ObservationIgnored private var timeout: Task<Void, Never>?
    @ObservationIgnored private var opening: Task<Void, Never>?

    init(openApp: @escaping OpenApp = { await UIApplication.shared.open($0, options: [.universalLinksOnly: true]) }) {
        self.openApp = openApp
    }

    func authorize(_ transaction: YunlefunSSO) async throws -> Outcome {
        guard pending == nil else { throw YunlefunAccountError.unavailable }
        let nonce = transaction.nonce
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                pending = (transaction, continuation)
                opening = Task { [weak self, openApp] in
                    guard !Task.isCancelled else { return }
                    let opened = await openApp(transaction.appAuthorizationURL)
                    guard let self, self.pending?.transaction.nonce == nonce else { return }
                    if opened { self.isWaiting = true }
                    else { self.finish(.success(.useWeb), nonce: nonce) }
                }
                timeout = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(YunlefunSSO.lifetime)) }
                    catch { return }
                    self?.finish(.failure(YunlefunAccountError.invalidCallback), nonce: nonce)
                }
            }
        } onCancel: {
            Task { @MainActor in self.finish(.failure(CancellationError()), nonce: nonce) }
        }
    }

    /// Only a valid callback for the pending transaction can finish the attempt.
    @discardableResult
    func handle(_ url: URL) -> Bool {
        guard YunlefunSSO.isCallback(url) else { return false }
        guard let pending, let result = try? pending.transaction.callbackResult(url) else { return true }
        let nonce = pending.transaction.nonce
        switch result {
        case .authorized(let fields): finish(.success(.authorized(fields)), nonce: nonce)
        case .denied: finish(.failure(CancellationError()), nonce: nonce)
        case .selectAccountOnWeb: finish(.success(.useWeb), nonce: nonce)
        }
        return true
    }

    func useWeb() {
        guard isWaiting, let nonce = pending?.transaction.nonce else { return }
        finish(.success(.useWeb), nonce: nonce)
    }

    func cancel() {
        guard let nonce = pending?.transaction.nonce else { return }
        finish(.failure(CancellationError()), nonce: nonce)
    }

    private func finish(_ result: Result<Outcome, Error>, nonce: String) {
        guard let pending, pending.transaction.nonce == nonce else { return }
        self.pending = nil
        isWaiting = false
        opening?.cancel()
        opening = nil
        timeout?.cancel()
        timeout = nil
        pending.continuation.resume(with: result)
    }
}
