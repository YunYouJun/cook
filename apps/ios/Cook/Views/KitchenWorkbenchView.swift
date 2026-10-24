import CookCore
import SwiftUI

/// 独立的全屏任务，展开时不受菜谱列表侧栏挤压；按实际宽度布局。
struct KitchenWorkbenchView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(KitchenWorkbench.self) private var workbench
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var pane: Pane = .recipe
    @State private var layout: LayoutChoice = .automatic
    let recipe: Recipe

    private enum Pane: String, CaseIterable {
        case recipe = "做法"
        case preparation = "备料与计时"
    }

    private enum LayoutChoice: String, CaseIterable {
        case automatic = "自动"
        case tabletop = "台面"
    }

    var body: some View {
        NavigationStack {
            GeometryReader { geometry in
                let frames = KitchenWorkbenchFrames.resolve(
                    size: geometry.size, division: geometry.kitchenDivision,
                    tabletop: layout == .tabletop, accessibility: dynamicTypeSize.isAccessibilitySize
                )
                let compact = frames.mode == .compact
                ZStack(alignment: .top) {
                    KitchenPaneLayout(frames: frames) {
                        instructions
                            .safeAreaInset(edge: .bottom, spacing: 0) {
                                if compact { timerShortcut }
                            }
                            .clipped()
                            .opacity(!compact || pane == .recipe ? 1 : 0)
                            .allowsHitTesting(!compact || pane == .recipe)
                            .accessibilityHidden(compact && pane != .recipe)
                        preparation(tabletop: frames.mode == .tabletop)
                            .clipped()
                            .opacity(!compact || pane == .preparation ? 1 : 0)
                            .allowsHitTesting(!compact || pane == .preparation)
                            .accessibilityHidden(compact && pane != .preparation)
                    }
                    if !frames.divider.isEmpty {
                        Rectangle().fill(.separator)
                            .frame(width: frames.divider.width, height: frames.divider.height)
                            .position(x: frames.divider.midX, y: frames.divider.midY)
                            .accessibilityHidden(true)
                    }
                    if compact {
                        Picker("工作台内容", selection: $pane) {
                            ForEach(Pane.allCases, id: \.self) { pane in
                                Text(pane.rawValue).tag(pane)
                            }
                        }
                        .pickerStyle(.segmented)
                        .padding(.horizontal)
                        .frame(height: 48)
                    }
                }
            }
            .navigationTitle(recipe.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("收起", systemImage: "chevron.down") { dismiss() }
                        .accessibilityIdentifier("kitchen.close")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("工作台布局", selection: $layout) {
                            ForEach(LayoutChoice.allCases, id: \.self) { choice in
                                Text(choice.rawValue).tag(choice)
                            }
                        }
                    } label: {
                        Label("布局，\(layout.rawValue)", systemImage: "rectangle.split.2x1")
                    }
                    .accessibilityIdentifier("kitchen.layout")
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
            RecipeTextContent(recipe: recipe, text: text)
                .accessibilityIdentifier("kitchen.instructions")
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

    private func preparation(tabletop: Bool) -> some View {
        KitchenPreparationView(recipe: recipe, ingredients: KitchenIngredients.list(
            text: store.text(for: recipe), fallback: store.data.normalizer.unique(recipe.stuff)
        ), tabletop: tabletop)
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
    @Environment(KitchenWorkbench.self) private var workbench
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var confirmingClear = false
    let recipe: Recipe
    let ingredients: [String]
    var tabletop = false

    private var prepared: Set<String> { workbench.state(for: recipe.id).prepared }
    private var preparedCount: Int { ingredients.filter { prepared.contains($0) }.count }

    var body: some View {
        Form {
            if !tabletop || dynamicTypeSize.isAccessibilitySize {
                KitchenTimerSection(recipe: recipe)
            }

            Section {
                if ingredients.isEmpty {
                    Text("这道菜还没有材料记录，请按做法准备。")
                        .foregroundStyle(.secondary)
                }
                ForEach(ingredients, id: \.self) { ingredient in
                    Button {
                        workbench.togglePrepared(ingredient, recipeID: recipe.id)
                    } label: {
                        Label {
                            Text(ingredient).foregroundStyle(.primary)
                        } icon: {
                            Image(systemName: prepared.contains(ingredient) ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(prepared.contains(ingredient) ? Color.accentColor : Color.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(ingredient)
                    .accessibilityValue(prepared.contains(ingredient) ? "已备好" : "未备好")
                    .accessibilityHint("轻点切换备料状态")
                    .accessibilityIdentifier("kitchen.ingredient.\(ingredient)")
                }
            } header: {
                HStack {
                    Text("备料清单")
                    Spacer()
                    Text("\(preparedCount) / \(ingredients.count)")
                        .monospacedDigit()
                        .accessibilityLabel("已备好 \(preparedCount) 项，共 \(ingredients.count) 项")
                }
            } footer: {
                Text("勾选已备好的材料。完整用料与用量请参照做法。")
            }

            if !prepared.isEmpty {
                Section {
                    Button("重新备料", role: .destructive) { confirmingClear = true }
                }
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if tabletop, !dynamicTypeSize.isAccessibilitySize {
                KitchenTimerPanel(recipe: recipe, compact: true)
                    .padding(.horizontal)
                    .padding(.vertical, 10)
                    .background(.bar)
            }
        }
        .listSectionSpacing(16)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("kitchen.preparation")
        .confirmationDialog("清空这道菜的备料勾选？", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("重新备料", role: .destructive) { workbench.clearPrepared(recipeID: recipe.id) }
        } message: {
            Text("计时器会继续保留。")
        }
        .sensoryFeedback(.selection, trigger: prepared)
    }
}

#Preview("备料清单") {
    KitchenPreparationView(
        recipe: Recipe(id: 1, name: "番茄炒蛋", stuff: ["番茄", "鸡蛋", "盐", "油"]),
        ingredients: ["番茄", "鸡蛋", "盐", "油"]
    )
    .environment(KitchenWorkbench(defaults: nil, notifications: .disabled))
}

#Preview("材料未收录") {
    KitchenPreparationView(recipe: Recipe(id: 2, name: "私人做法", stuff: []), ingredients: [])
        .environment(KitchenWorkbench(defaults: nil, notifications: .disabled))
}
