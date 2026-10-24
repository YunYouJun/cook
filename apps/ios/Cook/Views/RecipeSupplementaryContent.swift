import CookCore
import SwiftUI

/// 附加内容保留来源原文并支持定位步骤，不参与完成记录和用量计算。
enum RecipeSupplementaryContent {
    /// 只把独立、明确且唯一的步骤引用变成按钮，缺失或重号时保留原文。
    static func destination(for text: String, steps: [RecipePresentation.Step]) -> RecipePresentation.Step? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let expression = try? NSRegularExpression(pattern: #"^(?:继续\s*)?第\s*([0-9]+)\s*步[。！!]?\z"#),
              let match = expression.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)),
              let range = Range(match.range(at: 1), in: value),
              let number = Int(value[range]) else { return nil }
        let matches = steps.filter { $0.number == number }
        return matches.count == 1 ? matches[0] : nil
    }

    static func contains(_ offset: Int, in blocks: [RecipeTextBlock]) -> Bool {
        for block in blocks.prefix(offset).reversed() {
            if case let .heading(title) = block {
                return title.trimmingCharacters(in: .whitespacesAndNewlines) == "附加内容"
            }
        }
        return false
    }
}

struct RecipeSupplementaryHeading: View {
    var body: some View {
        Label("附加内容", systemImage: "lightbulb")
            .font(.title3.bold())
            .labelStyle(.titleAndIcon)
            .padding(.top, 12)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("recipe.supplementary.heading")
    }
}

struct RecipeSupplementaryCard: View {
    let text: String
    var number: Int? = nil
    var steps: [RecipePresentation.Step] = []
    var jump: (RecipePresentation.Step) -> Void = { _ in }

    private var lines: [String] { text.components(separatedBy: "\n") }
    private var sourceTitle: String? {
        guard lines.count > 1, let first = lines.first else { return nil }
        let title = first.trimmingCharacters(in: .whitespaces)
        guard title.count <= 28, title.hasSuffix("：") || title.hasSuffix(":") else { return nil }
        return String(title.dropLast())
    }
    private var style: Style {
        if let title = sourceTitle, title.contains("快速") { return .quick }
        if ["注意", "小心", "务必"].contains(where: { text.hasPrefix($0) || text.hasPrefix("特别" + $0) }) { return .caution }
        if ["改为", "替换", "代替"].contains(where: text.contains) { return .alternative }
        return .tip
    }
    private var title: String { sourceTitle ?? style.title }
    private var bodyLines: [String] { sourceTitle == nil ? lines : Array(lines.dropFirst()) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Group {
                    if let number {
                        Text("\(number)").font(.subheadline.weight(.semibold).monospacedDigit())
                    } else {
                        Image(systemName: style.symbol).font(.subheadline.weight(.semibold))
                    }
                }
                .foregroundStyle(style.color)
                .frame(width: 32, height: 32)
                .background(style.color.opacity(0.10), in: RoundedRectangle(cornerRadius: 10))
                .accessibilityHidden(number == nil)
                Text(recipeMarkdown(title))
                    .font(.subheadline.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
            }

            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(bodyLines.enumerated()), id: \.offset) { _, line in
                    if line.trimmingCharacters(in: .whitespaces).isEmpty {
                        Color.clear.frame(height: 4).accessibilityHidden(true)
                    } else {
                        let trimmed = line.trimmingCharacters(in: .whitespaces)
                        let isBullet = trimmed.hasPrefix("• ")
                        let content = isBullet ? String(trimmed.dropFirst(2)) : line
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            if isBullet {
                                Text("•").foregroundStyle(style.color).accessibilityHidden(true)
                            }
                            if let target = RecipeSupplementaryContent.destination(for: content, steps: steps) {
                                Button { jump(target) } label: {
                                    Label {
                                        Text(recipeMarkdown(content))
                                            .fixedSize(horizontal: false, vertical: true)
                                    } icon: {
                                        Image(systemName: "arrow.up.to.line")
                                    }
                                    .frame(minHeight: 32, alignment: .leading)
                                }
                                .buttonStyle(.bordered)
                                .textSelection(.disabled)
                                .accessibilityLabel("跳转到第 \(target.number) 步")
                                .accessibilityHint("查看对应步骤，不改变完成进度")
                                .accessibilityIdentifier("recipe.stepReference.\(target.number)")
                            } else {
                                Text(recipeMarkdown(content))
                                    .font(.body)
                                    .lineSpacing(3)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .textSelection(.enabled)
                            }
                        }
                    }
                }
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
    }

    private enum Style {
        case quick, alternative, caution, tip

        var title: String {
            switch self {
            case .quick: return "快速做法"
            case .alternative: return "替换说明"
            case .caution: return "注意事项"
            case .tip: return "小贴士"
            }
        }
        var symbol: String {
            switch self {
            case .quick: return "bolt.fill"
            case .alternative: return "arrow.triangle.2.circlepath"
            case .caution: return "exclamationmark.triangle"
            case .tip: return "lightbulb"
            }
        }
        var color: Color {
            switch self {
            case .quick, .caution: return .orange
            case .alternative: return .blue
            case .tip: return .secondary
            }
        }
    }
}

#Preview("附加内容") {
    let steps = RecipePresentation(text: RecipeText(blocks: [
        .heading("操作"), .list(ordered: true, start: 10, items: ["调味", "盛盘"]),
    ])).steps
    ScrollView {
        VStack(alignment: .leading, spacing: 16) {
            RecipeSupplementaryHeading()
            Text("这道菜根据不同的口味偏好，存在诸多版本，包括且不限于：")
                .font(.subheadline).foregroundStyle(.secondary)
            RecipeSupplementaryCard(text: "快速做法：\n• 直接在有`半熟鸡蛋`的锅中加入 `西红柿块`，与鸡蛋一起翻炒直至西红柿软烂\n• 继续 第 10 步", steps: steps)
            RecipeSupplementaryCard(text: "加入盐 改为 “加入两滴生抽”")
        }
        .padding()
    }
    .background(Color(uiColor: .systemGroupedBackground))
}
