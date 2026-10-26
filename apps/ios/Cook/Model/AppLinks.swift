import Foundation

/// 与 Web 共用 packages/shared/links.json，由 Xcode 作为资源打包。
struct AppLinks: Decodable {
    let wecomKf: URL

    static let shared: AppLinks = {
        guard let url = Bundle.main.url(forResource: "links", withExtension: "json") else {
            preconditionFailure("缺少共享链接资源，请重新生成 Xcode 工程并构建。")
        }
        do {
            return try JSONDecoder().decode(AppLinks.self, from: Data(contentsOf: url))
        } catch {
            preconditionFailure("共享链接资源无法解析：\(error)")
        }
    }()
}
