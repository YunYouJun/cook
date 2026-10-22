import CookCore
import Foundation
import Observation

/// 菜谱反馈、意见反馈和匿名筛选信号，发到 cook-feedback 云函数的 HTTP 地址，和 Web 用同一套数据（docs/dev/data.md）
@MainActor
@Observable
final class FeedbackClient {
    /// 这道菜有什么问题，和 Web 的 feedbackKinds 一致
    enum RecipeIssue: String, CaseIterable, Identifiable {
        case video, stuff, info, other

        var id: String { rawValue }

        var label: String {
            switch self {
            case .video: return "视频打不开"
            case .stuff: return "食材或厨具不对"
            case .info: return "难度或做法不对"
            case .other: return "其他问题"
            }
        }
    }

    /// 意见反馈的类型，和 Web 的 appFeedbackCategories 一致
    enum Category: String, CaseIterable, Identifiable {
        case bug, idea, recipe, other

        var id: String { rawValue }

        var label: String {
            switch self {
            case .bug: return "用着有问题"
            case .idea: return "功能建议"
            case .recipe: return "想要的菜没有"
            case .other: return "其他"
            }
        }

        var placeholder: String {
            switch self {
            case .bug: return "在哪个页面、做了什么、看到了什么"
            case .idea: return "想要什么功能，用在什么时候"
            case .recipe: return "菜名；有 B 站视频的话贴上链接"
            case .other: return "说说具体情况"
            }
        }
    }

    enum SendError: Error {
        case notConfigured
        case rejected(String)
    }

    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)

    /// 设置里「帮助改进推荐」开关存在 UserDefaults 的键，默认开
    nonisolated static let signalsKey = "shareSearchSignals"
    nonisolated static let maxNote = 500
    nonisolated static let maxContact = 100
    /// 提交不了时的兜底邮箱，和 Web 的 links.supportEmail、云乐坊的「问题反馈」邮箱一致（docs/decisions/0012-feedback-channels.md）
    nonisolated static let supportEmail = "kf@yunle.fun"

    /// 每次启动随机生成，只放在内存里，云函数用它按设备限额；不写盘，也不读 IDFV
    let reporter = UUID().uuidString
    var isConfigured: Bool { endpoint != nil }

    private let endpoint: URL?
    private let version: String
    private let sendsSignals: Bool
    private let defaults: UserDefaults
    private let transport: Transport
    @ObservationIgnored private var sentSignals = Set<String>()

    /// Debug 构建默认不发筛选信号，免得开发时的点击混进评测数据；菜谱反馈和意见反馈照常提交，方便联调
    init(
        endpoint: URL? = FeedbackClient.bundledEndpoint,
        version: String = FeedbackClient.bundledVersion,
        sendsSignals: Bool = FeedbackClient.releaseBuild,
        defaults: UserDefaults = .standard,
        transport: @escaping Transport = { try await URLSession.shared.data(for: $0) }
    ) {
        self.endpoint = endpoint
        self.version = version
        self.sendsSignals = sendsSignals
        self.defaults = defaults
        self.transport = transport
    }

    // MARK: 筛选信号

    /// 选了一组食材却没有能做的菜
    @discardableResult
    func signalEmpty(stuff: [String], tool: String?, mode: RecommendationMode) -> Task<Void, Never>? {
        signal(["type": "empty", "stuff": stuff, "tool": tool ?? "", "mode": mode.rawValue])
    }

    /// 在这组食材的推荐结果里点开了第 rank 道菜（从 0 开始）
    @discardableResult
    func signalOpen(stuff: [String], tool: String?, mode: RecommendationMode, recipeId: Int, rank: Int) -> Task<Void, Never>? {
        signal(["type": "open", "stuff": stuff, "tool": tool ?? "", "mode": mode.rawValue, "recipeId": recipeId, "rank": rank])
    }

    /// 同一组条件本次启动只报一次，失败也不重试，不影响界面
    private func signal(_ body: [String: Any]) -> Task<Void, Never>? {
        guard sendsSignals, signalsEnabled, isConfigured,
              let stuff = body["stuff"] as? [String], !stuff.isEmpty
        else {
            return nil
        }
        let key = Self.signalKey(body)
        guard sentSignals.insert(key).inserted else {
            return nil
        }
        var body = body
        body["action"] = "signal"
        return Task { _ = try? await self.post(body) }
    }

    var signalsEnabled: Bool {
        defaults.object(forKey: Self.signalsKey) as? Bool ?? true
    }

    static func signalKey(_ body: [String: Any]) -> String {
        let stuff = (body["stuff"] as? [String] ?? []).sorted().joined(separator: "、")
        let parts: [Any] = [body["type"] ?? "", stuff, body["tool"] ?? "", body["mode"] ?? "", body["recipeId"] ?? ""]
        return parts.map { "\($0)" }.joined(separator: "|")
    }

    // MARK: 反馈

    func report(_ recipe: Recipe, issue: RecipeIssue, note: String = "") async throws {
        try await post([
            "action": "report",
            "recipeId": recipe.id,
            "recipeName": recipe.name,
            "kind": issue.rawValue,
            "note": String(note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200)),
        ])
    }

    func send(_ category: Category, note: String, contact: String) async throws {
        try await post([
            "action": "feedback",
            "category": category.rawValue,
            "note": String(note.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxNote)),
            "contact": String(contact.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.maxContact)),
        ])
    }

    private func post(_ body: [String: Any]) async throws {
        guard let endpoint else {
            throw SendError.notConfigured
        }
        var body = body
        body["deviceId"] = reporter
        // 每条都带上来源和版本，汇总时能把 iOS 和 Web 分开看
        body["platform"] = "ios"
        body["version"] = version
        var request = URLRequest(url: endpoint, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await transport(request)
        let result = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        guard (response as? HTTPURLResponse)?.statusCode == 200, result?["ok"] as? Bool == true else {
            throw SendError.rejected(result?["code"] as? String ?? "unknown")
        }
    }

    // MARK: 邮件兜底

    /// 邮件无法打开时仍能保留收件人、类型、联系方式和平台信息。
    nonisolated static func mailText(topic: String, lines: [String], version: String = FeedbackClient.bundledVersion) -> String {
        let url = mailURL(topic: topic, lines: lines, version: version)
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let subject = query.first { $0.name == "subject" }?.value ?? ""
        let body = query.first { $0.name == "body" }?.value ?? ""
        return "收件人：\(supportEmail)\n主题：\(subject)\n\n\(body)"
    }

    /// 预填好的反馈邮件，云函数连不上或没配置时用；不需要账号，比 GitHub 门槛低。格式和 Web 的 buildFeedbackMail 一致
    nonisolated static func mailURL(topic: String, lines: [String], version: String = FeedbackClient.bundledVersion) -> URL {
        let body = (lines.filter { !$0.isEmpty } + ["", "---", "来自 云食册（iOS \(version)）"]).joined(separator: "\n")
        // URLQueryItem 不转义 & 和 +，正文里有这些字符会被截断，所以手动转义
        let allowed = CharacterSet.urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=+?#"))
        func encode(_ text: String) -> String {
            text.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
        }
        return URL(string: "mailto:\(supportEmail)?subject=\(encode("【云食册反馈】\(topic)"))&body=\(encode(body))")!
    }

    // MARK: 构建配置

    /// 构建时由 COOK_FEEDBACK_URL 写进 Info.plist；没填时意见反馈改成发邮件，菜谱反馈改成跳 GitHub
    nonisolated static var bundledEndpoint: URL? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "CookFeedbackURL") as? String,
              let url = URL(string: value.trimmingCharacters(in: .whitespaces)),
              url.scheme == "https", url.host != nil
        else {
            return nil
        }
        return url
    }

    nonisolated static var bundledVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    nonisolated static var releaseBuild: Bool {
        #if DEBUG
            return false
        #else
            return true
        #endif
    }
}
