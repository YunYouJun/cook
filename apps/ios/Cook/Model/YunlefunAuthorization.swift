import AuthenticationServices
import Foundation
import UIKit

@MainActor
protocol YunlefunAuthorizing {
    func authorize(_ transaction: YunlefunSSO) async throws -> URL
    func cancel()
}

/// HTTPS 回调要求 webcredentials 域名关联，避免其他 App 冒充此公开客户端。
@MainActor
final class SystemYunlefunAuthorization: NSObject, YunlefunAuthorizing, ASWebAuthenticationPresentationContextProviding {
    private var session: ASWebAuthenticationSession?
    private var continuation: CheckedContinuation<URL, Error>?
    private var timeout: Task<Void, Never>?
    private var anchor: UIWindow?
    private var attemptID: UUID?

    func authorize(_ transaction: YunlefunSSO) async throws -> URL {
        guard #available(iOS 17.4, *) else { throw YunlefunAccountError.requiresUpdate }
        guard session == nil,
              let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
                .filter({ $0.activationState == .foregroundActive }).flatMap(\.windows).first(where: \.isKeyWindow) else {
            throw YunlefunAccountError.unavailable
        }
        anchor = window
        let id = UUID()
        attemptID = id
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                let session = ASWebAuthenticationSession(url: transaction.authorizationURL,
                    callback: .https(host: "cook.yunyoujun.cn", path: "/auth/callback")) { [weak self] url, error in
                    Task { @MainActor in
                        if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                            self?.finish(.failure(CancellationError()), id: id)
                        } else if let url { self?.finish(.success(url), id: id) }
                        else { self?.finish(.failure(YunlefunAccountError.authorizationFailed), id: id) }
                    }
                }
                session.presentationContextProvider = self
                self.session = session
                guard session.start() else {
                    finish(.failure(YunlefunAccountError.unavailable), id: id)
                    return
                }
                timeout = Task { [weak self] in
                    do { try await Task.sleep(for: .seconds(YunlefunSSO.lifetime)) }
                    catch { return }
                    guard self?.attemptID == id else { return }
                    self?.session?.cancel()
                    self?.finish(.failure(YunlefunAccountError.invalidCallback), id: id)
                }
            }
        } onCancel: {
            Task { @MainActor in
                if self.attemptID == id { self.cancel() }
            }
        }
    }

    func cancel() {
        guard let id = attemptID else { return }
        session?.cancel()
        finish(.failure(CancellationError()), id: id)
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        anchor ?? ASPresentationAnchor()
    }

    private func finish(_ result: Result<URL, Error>, id: UUID) {
        guard attemptID == id else { return }
        attemptID = nil
        let pending = continuation
        continuation = nil
        timeout?.cancel()
        timeout = nil
        session = nil
        anchor = nil
        pending?.resume(with: result)
    }
}
