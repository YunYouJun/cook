import CookCore
import SwiftUI

/// 独立的全屏任务，展开时不受菜谱列表侧栏挤压；按实际宽度布局。
struct KitchenWorkbenchView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(KitchenWorkbench.self) private var workbench
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var pane: Pane = .recipe
    let recipe: Recipe

    private enum Pane: String, CaseIterable {
        case recipe = "做法"
        case preparation = "备料与计时"
    }

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                if geometry.size.width >= 760, !dynamicTypeSize.isAccessibilitySize {
                    HStack(spacing: 0) {
                        instructions
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        Divider()
                        preparation
                            .frame(width: min(420, max(320, geometry.size.width * 0.38)))
                    }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("kitchen.wide")
                } else {
                    VStack(spacing: 0) {
                        Picker("工作台内容", selection: $pane) {
                            ForEach(Pane.allCases, id: \.self) { pane in
                                Text(pane.rawValue).tag(pane)
                            }
                        }
                        .pickerStyle(.segmented)
                        .padding(.horizontal)
                        .padding(.vertical, 8)

                        // 两个原生滚动视图保留身份，切换时不重建正文或丢失阅读位置。
                        ZStack {
                            instructions
                                .safeAreaInset(edge: .bottom) {
                                    timerShortcut
                                }
                                .opacity(pane == .recipe ? 1 : 0)
                                .allowsHitTesting(pane == .recipe)
                                .accessibilityHidden(pane != .recipe)
                            preparation
                                .opacity(pane == .preparation ? 1 : 0)
                                .allowsHitTesting(pane == .preparation)
                                .accessibilityHidden(pane != .preparation)
                        }
                    }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("kitchen.compact")
                }
            }
            .navigationTitle(recipe.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("收起", systemImage: "chevron.down") { dismiss() }
                        .accessibilityIdentifier("kitchen.close")
                }
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 2) {
                        Text(recipe.name).font(.headline)
                        Text("厨房工作台").font(.caption).foregroundStyle(.secondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    @ViewBuilder
    private var instructions: some View {
        if let text = store.text(for: recipe) {
            RecipeTextContent(recipe: recipe, text: text, preparationInSidebar: true)

        } else {
            ContentUnavailableView {
                Label("暂未收录图文做法", systemImage: "book.closed")
            } description: {
                Text("可以先备料和计时，完整步骤请查看原做法。")
            } actions: {
                if let url = recipe.videoURL ?? recipe.link.flatMap(URL.init(string:)) {
                    Link("查看原做法", destination: url)
                }
            }
        }
    }

    private var preparation: some View {
        KitchenPreparationView(recipe: recipe, text: store.text(for: recipe))
    }

    private var timerShortcut: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let timer = workbench.state(for: recipe.id).timer
            Button {
                pane = .preparation
            } label: {
                HStack {
                    Label(timer.phase(at: context.date) == .finished ? "计时结束" : "计时器", systemImage: "timer")
                    Spacer()
                    Text(timer.display(at: context.date)).monospacedDigit()
                    Image(systemName: "chevron.right").font(.caption)
                }
                .padding()
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .background(.bar)
            .accessibilityIdentifier("kitchen.timerShortcut")
        }
    }
}

struct KitchenPreparationView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(KitchenWorkbench.self) private var workbench
    @State private var confirmingClear = false
    let recipe: Recipe
    let text: RecipeText?

    private var presentation: RecipePresentation? { text.map(RecipePresentation.init) }
    private var amounts: RecipeAmounts {
        RecipeAmounts(rows: presentation?.allQuantities ?? [], servings: workbench.state(for: recipe.id).servings)
    }
    private var canAdjustServings: Bool {
        amounts.supportsServings || (presentation?.steps ?? []).contains { amounts.instruction($0.text).hasAdjustedAmounts }
    }
    private var ingredients: [String] {
        KitchenIngredients.list(text: text, fallback: store.data.normalizer.unique(recipe.stuff))
    }
    private var foods: [String] { ingredients.filter { !KitchenIngredients.isTool($0) } }
    private var tools: [String] {
        let explicit = ingredients.filter(KitchenIngredients.isTool)
        // 正文已列出的工具优先；补充菜谱元数据中明确记录的厨具。
        let names = Set(explicit.map { store.toolLabel(RecipePresentation.ingredientName($0)) })
        return uniqueStrings(explicit + recipe.tools.map(store.toolLabel).filter { !names.contains($0) })
    }
    private var allItems: [String] { uniqueStrings(foods + tools) }
    private var prepared: Set<String> { workbench.state(for: recipe.id).prepared }
    private var preparedCount: Int { allItems.filter { prepared.contains($0) }.count }

    var body: some View {
        Form {
            KitchenTimerSection(recipe: recipe)

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Label("备料清单", systemImage: "checklist").font(.headline)
                        Spacer()
                        Text("\(preparedCount) / \(allItems.count)")
                            .font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    ProgressView(value: Double(preparedCount), total: Double(max(allItems.count, 1)))
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("已备好 \(preparedCount) 项，共 \(allItems.count) 项")

                if let description = presentation?.servingDescription {
                    Text(description).font(.footnote).foregroundStyle(.secondary)
                }
                if canAdjustServings {
                    RecipeServingControl(recipeID: recipe.id, rows: presentation?.allQuantities ?? [], identifier: "kitchen.servings")
                }
                if allItems.isEmpty {
                    Text("这道菜还没有材料记录，请按做法准备。")
                        .foregroundStyle(.secondary)
                }
                ForEach(foods, id: \.self) { ingredient in
                    preparationRow(ingredient, isTool: false)
                }
            } footer: {
                Text(canAdjustServings ? "按所选份数备料。调整份数后，用量变化的材料需重新勾选。" : "轻点勾选已备好的原料；未注明份数的用量保留原配方。")
            }

            if !tools.isEmpty {
                Section("厨具") {
                    ForEach(tools, id: \.self) { tool in
                        preparationRow(tool, isTool: true)
                    }
                }
            }

            if preparedCount > 0 {
                Section {
                    Button("重新备料", role: .destructive) { confirmingClear = true }
                }
            }
        }
        .listSectionSpacing(16)
        .accessibilityIdentifier("kitchen.preparation")
        .confirmationDialog("清空这道菜的备料勾选？", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("重新备料", role: .destructive) { workbench.clearPrepared(recipeID: recipe.id) }
        } message: {
            Text("操作步骤和计时器会继续保留。")
        }
        .sensoryFeedback(.selection, trigger: prepared)
    }

    private func preparationRow(_ item: String, isTool: Bool) -> some View {
        let isPrepared = prepared.contains(item)
        let amount = amounts.amount(for: item)
        return Button {
            workbench.togglePrepared(item, recipeID: recipe.id)
        } label: {
            HStack(alignment: .center, spacing: 10) {
                RecipeIngredientIcon(name: item, isTool: isTool)
                VStack(alignment: .leading, spacing: 5) {
                    Text(recipeMarkdown(item))
                        .foregroundStyle(isPrepared ? Color.secondary : Color.primary)
                        .strikethrough(isPrepared)
                    if let amount {
                        Text(recipeMarkdown(amount.text))
                            .font(.caption).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        if let note = amount.note {
                            Text(note).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: isPrepared ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isPrepared ? Color.accentColor : Color.secondary)
            }
            .padding(.vertical, 4)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel([item, amount?.text].compactMap { $0 }.joined(separator: "，"))
        .accessibilityValue(isPrepared ? "已备好" : "未备好")
        .accessibilityHint("轻点切换备料状态")
        .accessibilityIdentifier("kitchen.ingredient.\(item)")
    }
}

#Preview("备料清单") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        KitchenPreparationView(
            recipe: Recipe(id: 1, name: "番茄炒蛋", stuff: ["番茄", "鸡蛋", "盐", "油"], tools: ["一口大锅"]),
            text: nil
        )
        .environment(store)
        .environment(KitchenWorkbench(defaults: nil, notifications: .disabled))
    }
}
