import CookCore
import SwiftUI

/// 原文中的预估数据；没有记录的指标不补造数值。
struct RecipeMetricsView: View {
    let presentation: RecipePresentation
    let difficulty: String?

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 160), alignment: .leading)], spacing: 12) {
            if let stars = presentation.difficulty {
                metric("烹饪难度", icon: "chart.bar", color: .blue) {
                    HStack(spacing: 4) {
                        ForEach(1 ... 5, id: \.self) { index in
                            Image(systemName: index <= stars ? "star.fill" : "star")
                                .font(.caption)
                                .foregroundStyle(index <= stars ? Color.accentColor : Color.secondary)
                        }
                        Text("\(stars) / 5").font(.caption).foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("难度 \(stars) 星，满分 5 星")
                }
            } else if let difficulty, !difficulty.isEmpty {
                metric("烹饪难度", icon: "chart.bar", color: .blue) {
                    Text(difficulty).font(.headline)
                }
            }
            if let calories = presentation.calories {
                metric("预估热量", icon: "flame", color: .orange) {
                    Text(calories).font(.headline).fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .accessibilityIdentifier("recipe.metrics")
    }

    private func metric<Content: View>(_ title: String, icon: String, color: Color, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: icon)
                .font(.subheadline)
                .foregroundStyle(color)
            content()
        }
        .frame(maxWidth: .infinity, minHeight: 62, alignment: .leading)
        .padding(16)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
    }
}

/// 图标来自现有食材词典；调料和工具用系统符号，原名称和可选说明仍完整显示。
struct RecipeIngredientIcon: View {
    @Environment(RecipeStore.self) private var store
    let name: String
    var isTool = false

    private var normalized: String {
        let name = RecipePresentation.ingredientName(name)
        return store.data.normalizer.normalize([name]).first ?? name
    }

    var body: some View {
        Group {
            if let emoji = store.emoji(forStuff: normalized) {
                Text(emoji).font(.title2)
            } else {
                Image(systemName: symbol)
                    .font(.title3)
                    .foregroundStyle(isTool ? Color.secondary : Color.accentColor)
            }
        }
        .frame(width: 36, height: 36)
        .background(Color(uiColor: .tertiarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 10))
        .accessibilityHidden(true)
    }

    private var symbol: String {
        if isTool {
            if name.contains("刀") { return "scissors" }
            if name.contains("微波") { return "microwave" }
            if name.contains("烤箱") { return "oven" }
            return "frying.pan"
        }
        if name.contains("油") || name.contains("醋") || name.contains("酒") { return "drop" }
        if name.contains("盐") || name.contains("糖") || name.contains("粉") { return "circle.grid.3x3.fill" }
        if name.contains("葱") || name.contains("叶") { return "leaf" }
        return "fork.knife"
    }
}

/// 两处入口共享按菜谱保存的份数，读图文和备料时都能直接调整。
struct RecipeServingControl: View {
    @Environment(KitchenWorkbench.self) private var workbench
    let recipeID: Int
    let rows: [RecipePresentation.Quantity]
    let identifier: String
    var compact = false

    private var servings: Int { workbench.state(for: recipeID).servings }

    var body: some View {
        Stepper(value: Binding(
            get: { servings },
            set: { workbench.setServings($0, recipeID: recipeID, quantities: rows) }
        ), in: RecipeAmounts.servingRange) {
            if compact {
                Label("\(servings) 份", systemImage: "person.2")
                    .font(.subheadline).monospacedDigit()
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    Label("制作份数", systemImage: "person.2")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("\(servings) 份").font(.headline).monospacedDigit()
                }
            }
        }
        .accessibilityLabel("制作份数")
        .accessibilityValue("\(servings) 份")
        .accessibilityIdentifier(identifier)
    }
}

struct RecipeQuantityTable: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let rows: [RecipePresentation.Quantity]
    let amounts: RecipeAmounts

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("原料").frame(maxWidth: .infinity, alignment: .leading)
                Text(amounts.supportsServings ? "\(amounts.servings) 份用量" : "原文用量").frame(maxWidth: .infinity, alignment: .leading)
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(14)
            .background(Color.accentColor.opacity(0.07))
            ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                if index > 0 { Divider().padding(.horizontal, 14) }
                let layout = dynamicTypeSize.isAccessibilitySize
                    ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
                    : AnyLayout(HStackLayout(alignment: .top, spacing: 14))
                layout {
                    HStack(spacing: 8) {
                        RecipeIngredientIcon(name: row.name)
                        Text(recipeMarkdown(row.name)).font(.subheadline.weight(.medium))
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    let amount = amounts.amount(for: row)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(recipeMarkdown(amount.text))
                            .font(.subheadline.weight(.medium))
                            .accessibilityIdentifier("recipe.amount.\(row.name)")
                        if let note = amount.note {
                            Text(note).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
                }
                .padding(14)
            }
        }
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("recipe.quantities")
    }
}

struct RecipeStepCard: View {
    @ScaledMetric(relativeTo: .body) private var markerSize = 32.0
    let step: RecipePresentation.Step
    var instruction: RecipeInstructionAmounts? = nil
    var servings: Int = 1
    var highlighted: Bool = false
    let completed: Bool
    let current: Bool
    let toggle: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Button(action: toggle) {
                HStack(alignment: .top, spacing: 12) {
                    ZStack {
                        Circle().fill(completed ? Color.green.opacity(0.12) : current ? Color.accentColor : Color.secondary.opacity(0.08))
                        if completed {
                            Image(systemName: "checkmark")
                                .font(.subheadline.weight(.semibold)).foregroundStyle(.green)
                        } else {
                            Text("\(step.number)").font(.subheadline.weight(.semibold).monospacedDigit())
                                .foregroundStyle(current ? Color.white : Color.secondary)
                        }
                    }
                    .frame(width: markerSize, height: markerSize)
                    .frame(minHeight: 44)
                    .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 6) {
                        Text(recipeMarkdown(instruction?.text ?? step.text))
                            .font(.body)
                            .foregroundStyle(completed ? Color.secondary : Color.primary)
                            .lineSpacing(3)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("recipe.stepText.\(step.number)")
                        if instruction?.hasAdjustedAmounts == true {
                            Text("用量提示（当前 \(servings) 份）")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        if instruction?.hasUnresolvedFormula == true {
                            Text("部分用量需按原文确认")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .frame(minHeight: 44, alignment: .leading)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                .overlay {
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Color.accentColor.opacity(highlighted ? 0.09 : current ? 0.04 : 0))
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                    RoundedRectangle(cornerRadius: 16)
                        .strokeBorder(highlighted ? Color.accentColor : current ? Color.accentColor.opacity(0.35) : Color.clear,
                                      lineWidth: highlighted ? 2 : 1)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                // 将文字、编号和四周留白都纳入同一个原生按钮的热区。
                .contentShape(RoundedRectangle(cornerRadius: 16))
            }
            .buttonStyle(RecipeStepButtonStyle())
            .accessibilityLabel(Text("第 \(step.number) 步，") + Text(recipeMarkdown(instruction?.text ?? step.text)))
            .accessibilityValue(completed ? "已完成" : "未完成")
            .accessibilityHint(completed ? "轻点撤销完成" : current ? "轻点完成并前往下一步" : "轻点标记完成")
            .accessibilityIdentifier("recipe.step.\(step.number)")
            .contextMenu {
                Button("复制步骤", systemImage: "doc.on.doc") {
                    UIPasteboard.general.string = instruction?.text ?? step.text
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("recipe.stepCard.\(step.number)")
        .accessibilityValue(highlighted ? "已定位" : "")
    }
}

private struct RecipeStepButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.65 : 1)
    }
}

struct RecipeStepProgress: View {
    @Environment(KitchenWorkbench.self) private var workbench
    @State private var confirmingReset = false
    let recipeID: Int
    let steps: [RecipePresentation.Step]
    let jump: (RecipePresentation.Step) -> Void

    private var completed: Set<String> { workbench.state(for: recipeID).completedSteps }
    private var count: Int { steps.filter { completed.contains($0.id) }.count }
    private var next: RecipePresentation.Step? { steps.first { !completed.contains($0.id) } }

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(count == steps.count ? "做好了，开饭吧" : "烹饪进度")
                        .font(.subheadline.weight(.semibold))
                    Text("已完成 \(count) / \(steps.count) 步")
                        .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        .accessibilityIdentifier("recipe.stepProgress")
                }
                Spacer(minLength: 0)
                if let next {
                    Button("第 \(next.number) 步", systemImage: "arrow.down") { jump(next) }
                        .font(.subheadline)
                        .buttonStyle(.bordered)
                        .accessibilityLabel("继续第 \(next.number) 步")
                        .accessibilityIdentifier("recipe.continueStep")
                }
                if count > 0 {
                    Button("重置步骤", systemImage: "arrow.counterclockwise") { confirmingReset = true }
                        .labelStyle(.iconOnly)
                        .frame(minWidth: 44, minHeight: 44)
                }
            }
            // 重置按钮出现前后保留相同高度，避免步骤在完成或撤销时上下跳动。
            .frame(minHeight: 44)
            ProgressView(value: Double(count), total: Double(max(steps.count, 1)))
                .tint(count == steps.count ? .green : .accentColor)
                .accessibilityLabel("烹饪进度")
                .accessibilityValue("已完成 \(count) 步，共 \(steps.count) 步")
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(.bar)
        .accessibilityElement(children: .contain)
        .confirmationDialog("重新记录操作步骤？", isPresented: $confirmingReset, titleVisibility: .visible) {
            Button("重置步骤", role: .destructive) { workbench.clearSteps(recipeID: recipeID) }
        } message: {
            Text("只清除步骤完成记录，备料和计时器会保留。")
        }
    }
}

func recipeMarkdown(_ text: String) -> AttributedString {
    let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
    return (try? AttributedString(markdown: text, options: options)) ?? AttributedString(text)
}

private struct RecipeStepCardsPreview: View {
    private let steps = RecipePresentation(text: RecipeText(blocks: [
        .heading("操作"),
        .list(ordered: true, start: 1, items: ["蒜剁成蒜泥", "葱切段", "姜切成姜片", "加姜、盐、酱油、糖进行腌制 30–40 分钟"]),
    ])).steps

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("配菜处理").font(.headline).padding(.bottom, 6)
                ForEach(steps) { step in
                    RecipeStepCard(step: step, completed: step.number == 1, current: step.number == 2, toggle: {})
                }
            }
            .padding()
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }
}

#Preview("步骤 · 进行中") {
    RecipeStepCardsPreview()
}

#Preview("步骤 · 深色") {
    RecipeStepCardsPreview().preferredColorScheme(.dark)
}

#Preview("步骤 · 辅助字号") {
    RecipeStepCardsPreview().environment(\.dynamicTypeSize, .accessibility3)
}
