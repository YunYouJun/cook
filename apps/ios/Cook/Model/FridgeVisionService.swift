import CookCore
import Foundation
import ImageIO
import UniformTypeIdentifiers

enum FridgeVisionError: LocalizedError {
    case unreadablePhoto, loginRequired, membershipRequired, rateLimited, unavailable, invalidResponse, timedOut, offline, providerChanged

    var errorDescription: String? {
        switch self {
        case .unreadablePhoto: return "无法处理这张照片，请重新拍照或选择另一张。"
        case .loginRequired: return "请登录云乐坊账号后使用 AI 识图。"
        case .membershipRequired: return "冰箱 AI 识图需要有效云乐坊会员。"
        case .rateLimited: return "AI 识图的使用次数暂时已用完，可以稍后再试。"
        case .unavailable: return "AI 识图暂时不可用，可以用离线拍照或手动添加。"
        case .invalidResponse: return "这次没有收到可用的识别结果，请换一张照片重试。"
        case .timedOut: return "AI 识图超时，请重试。"
        case .offline: return "AI 识图需要联网，离线拍照识别仍可使用。"
        case .providerChanged: return "识图服务已更换，请核对新的照片接收方后再确认上传。"
        }
    }
}

enum FridgeVisionProvider: String, Codable, Sendable {
    case qwen
    case deepSeek = "deepseek"
    case cloudBase = "cloudbase"

    var photoProcessingDescription: String {
        switch self {
        case .qwen: return "经云乐坊（腾讯云 CloudBase）发送给阿里云百炼识别"
        case .deepSeek: return "经云乐坊（腾讯云 CloudBase）发送给 DeepSeek 识别"
        case .cloudBase: return "发送到云乐坊使用的腾讯云 CloudBase，由托管的 DeepSeek 模型识别"
        }
    }
}

struct FridgeVisionPhoto: Sendable {
    // The production HTTP gateway accepts a 96 KiB JSON body. Base64 and the envelope need room too.
    static let maxJPEGBytes = 70 * 1024
    let jpeg: Data

    static func prepare(_ data: Data) async throws -> Self {
        let task = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            guard data.count <= 30 * 1024 * 1024,
                  let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary)
            else { throw FridgeVisionError.unreadablePhoto }

            let attempts: [(size: Int, quality: Double)] = [
                (1600, 0.8), (1280, 0.76), (1024, 0.72), (800, 0.66), (640, 0.58), (512, 0.48),
            ]
            for (size, quality) in attempts {
                try Task.checkCancellation()
                guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                    kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true,
                    kCGImageSourceThumbnailMaxPixelSize: size,
                ] as CFDictionary) else { break }
                let encoded = NSMutableData()
                guard let output = CGImageDestinationCreateWithData(encoded, UTType.jpeg.identifier as CFString, 1, nil) else { break }
                // Encode pixels only: the original EXIF, GPS, file name and other source metadata are not copied.
                CGImageDestinationAddImage(output, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
                if CGImageDestinationFinalize(output), encoded.length <= maxJPEGBytes {
                    return Self(jpeg: encoded as Data)
                }
            }
            throw FridgeVisionError.unreadablePhoto
        }
        return try await withTaskCancellationHandler {
            try await task.value
        } onCancel: {
            task.cancel()
        }
    }
}

struct FridgeVisionCandidate: Identifiable, Equatable {
    static let limit = 20
    let id = UUID()
    var name: String
    var included = true

    static func names(_ candidates: [Self], normalizer: StuffNormalizer) -> [String]? {
        let names = normalizer.normalize(candidates.filter(\.included).map(\.name))
            .map(\.precomposedStringWithCanonicalMapping)
        guard names.allSatisfy(validName) else { return nil }
        var seen = Set<String>()
        return names.filter { seen.insert($0).inserted }
    }

    static func validName(_ name: String) -> Bool {
        let scalars = name.unicodeScalars
        return (1...30).contains(scalars.count) && scalars.contains(where: isLetterOrNumber)
            && scalars.allSatisfy { isLetterOrNumber($0) || " ·-（）()".unicodeScalars.contains($0) }
    }

    private static func isLetterOrNumber(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .decimalNumber, .letterNumber, .otherNumber:
            return true
        default:
            return false
        }
    }
}

struct FridgeVisionService: Sendable {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, HTTPURLResponse)
    static let productionURL = URL(string: "https://api.yunle.fun/cook-fridge-vision")!
    private static let maxResponseBytes = 8192
    static var configuredEndpoint: URL? {
        guard CookFeatures.hasAnyOnlineAICapability,
              let value = Bundle.main.object(forInfoDictionaryKey: "CookFridgeVisionServiceURL") as? String,
              value == productionURL.absoluteString else { return nil }
        return productionURL
    }

    let endpoint: URL
    private let transport: Transport

    init(endpoint: URL, transport: Transport? = nil) {
        self.endpoint = endpoint
        self.transport = transport ?? { try await Self.sendLive($0) }
    }

    @MainActor
    func memberProvider(using accounts: YunlefunAccountStore,
                        rejecting rejected: YunlefunCredential? = nil) async throws -> FridgeVisionProvider? {
        let revision = accounts.sessionRevision
        func validateSession() throws {
            try Task.checkCancellation()
            guard accounts.sessionRevision == revision else { throw CancellationError() }
        }

        let credential = try await accounts.credentialForRequest(rejecting: rejected)
        try validateSession()
        let provider: FridgeVisionProvider?
        do {
            provider = try await memberProvider(credential: credential)
        } catch FridgeVisionError.loginRequired where rejected == nil {
            try validateSession()
            let renewed = try await accounts.credentialForRequest(rejecting: credential)
            try validateSession()
            provider = try await memberProvider(credential: renewed)
        }
        try validateSession()
        return provider
    }

    func memberProvider(credential: YunlefunCredential) async throws -> FridgeVisionProvider? {
        try await memberProvider(authorization: .yunlefunBearer(credential))
    }

    func memberProvider(authorization: CookAIAuthorization) async throws -> FridgeVisionProvider? {
        let data = try await send(request(method: "GET", timeout: 15, authorization: authorization))
        guard let wire = try? JSONDecoder().decode(MembershipResponse.self, from: data), wire.ok, wire.schemaVersion == 1
        else { throw FridgeVisionError.invalidResponse }
        return wire.member ? wire.provider : nil
    }

    func recognize(_ photo: FridgeVisionPhoto, provider: FridgeVisionProvider, credential: YunlefunCredential) async throws -> [String] {
        try await recognize(photo, provider: provider, authorization: .yunlefunBearer(credential))
    }

    func recognize(_ photo: FridgeVisionPhoto, provider: FridgeVisionProvider, authorization: CookAIAuthorization) async throws -> [String] {
        guard (1...FridgeVisionPhoto.maxJPEGBytes).contains(photo.jpeg.count) else { throw FridgeVisionError.unreadablePhoto }

        var request = try request(method: "POST", timeout: 40, authorization: authorization)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let encoder = JSONEncoder()
        encoder.outputFormatting = .withoutEscapingSlashes
        request.httpBody = try encoder.encode(WireRequest(provider: provider, image: photo.jpeg))

        let data = try await send(request)
        guard let wire = try? JSONDecoder().decode(WireResponse.self, from: data), wire.ok, wire.schemaVersion == 1,
              wire.ingredients.count <= FridgeVisionCandidate.limit, wire.ingredients.allSatisfy(FridgeVisionCandidate.validName),
              Set(wire.ingredients).count == wire.ingredients.count else { throw FridgeVisionError.invalidResponse }
        return wire.ingredients
    }

    private func request(method: String, timeout: TimeInterval, authorization: CookAIAuthorization) throws -> URLRequest {
        guard endpoint == Self.productionURL else { throw FridgeVisionError.unavailable }
        var request = URLRequest(url: endpoint)
        request.httpMethod = method
        request.timeoutInterval = timeout
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        switch authorization {
        case let .yunlefunBearer(credential):
            guard authorization.isValidForTransmission, credential.accessToken.utf8.count <= 8192 else {
                throw FridgeVisionError.loginRequired
            }
        case .appleJWS:
            guard authorization.isValidForTransmission else { throw FridgeVisionError.membershipRequired }
        }
        request.setValue(authorization.headerValue, forHTTPHeaderField: "Authorization")
        return request
    }

    private func send(_ request: URLRequest) async throws -> Data {
        try Task.checkCancellation()
        do {
            let (data, response) = try await transport(request)
            try Task.checkCancellation()
            guard response.url == endpoint, data.count <= Self.maxResponseBytes else { throw FridgeVisionError.invalidResponse }
            switch response.statusCode {
            case 200: return data
            case 400, 413: throw FridgeVisionError.unreadablePhoto
            case 401: throw FridgeVisionError.loginRequired
            case 403 where (try? JSONDecoder().decode(ErrorResponse.self, from: data))?.code == "MEMBERSHIP_REQUIRED":
                throw FridgeVisionError.membershipRequired
            case 409: throw FridgeVisionError.providerChanged
            case 429: throw FridgeVisionError.rateLimited
            case 502: throw FridgeVisionError.invalidResponse
            case 504: throw FridgeVisionError.timedOut
            default: throw FridgeVisionError.unavailable
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError {
            if Task.isCancelled || error.code == .cancelled { throw CancellationError() }
            switch error.code {
            case .timedOut: throw FridgeVisionError.timedOut
            case .notConnectedToInternet, .networkConnectionLost, .cannotFindHost, .cannotConnectToHost, .dnsLookupFailed:
                throw FridgeVisionError.offline
            default: throw FridgeVisionError.unavailable
            }
        }
    }

    private struct WireRequest: Encodable {
        let schemaVersion = 1
        let consentVersion = 1
        let provider: FridgeVisionProvider
        let image: Data
    }

    private struct MembershipResponse: Decodable {
        let ok: Bool
        let schemaVersion: Int
        let member: Bool
        let provider: FridgeVisionProvider
    }

    private struct ErrorResponse: Decodable { let code: String }

    private struct WireResponse: Decodable {
        let ok: Bool
        let schemaVersion: Int
        let ingredients: [String]
    }

    private static func sendLive(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.urlCache = nil
        configuration.timeoutIntervalForResource = 45
        let session = URLSession(configuration: configuration, delegate: FridgeVisionRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await session.bytes(for: request)
        guard let http = response as? HTTPURLResponse else { throw FridgeVisionError.invalidResponse }
        var data = Data()
        for try await byte in bytes {
            guard data.count < maxResponseBytes else { throw FridgeVisionError.invalidResponse }
            data.append(byte)
        }
        return (data, http)
    }
}

private final class FridgeVisionRedirectPolicy: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? { nil }
}
