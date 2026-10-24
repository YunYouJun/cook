import Foundation

/// 投稿仍通过现有 recipe 类别提交，以明确的字段区分「想要的菜没有」。
struct RecipeContributionDraft {
    var name = ""
    var source = ""
    var details = ""

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var trimmedSource: String { source.trimmingCharacters(in: .whitespacesAndNewlines) }

    var hasValidSource: Bool {
        guard !trimmedSource.contains(where: { $0.isWhitespace }),
              let url = URL(string: trimmedSource),
              let scheme = url.scheme?.lowercased(), ["https", "http"].contains(scheme),
              let host = url.host, !host.isEmpty
        else { return false }
        return true
    }

    var note: String {
        var lines = ["菜谱投稿", "菜名：\(trimmedName)", "做法链接：\(trimmedSource)"]
        let explanation = details.trimmingCharacters(in: .whitespacesAndNewlines)
        if !explanation.isEmpty { lines.append("补充说明：\(explanation)") }
        return lines.joined(separator: "\n")
    }

    func canSubmit(maxLength: Int) -> Bool {
        !trimmedName.isEmpty && hasValidSource && note.count <= maxLength
    }
}
