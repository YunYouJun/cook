import CookCore
import SwiftUI

/// A second presentation of CookSession. All matching, filters and destinations
/// are the same ones used by the ingredient list.
struct CloudKitchenView: View {
    @Binding var presentation: CookPresentation
    @Binding var input: String
    let inputMessage: String?
    let submitInput: () -> Void
    var previewInput: (() -> Void)? = nil
    let decide: (RecipeRoute) -> Void
    let showResults: () -> Void
    var isDetailPresented = false
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .caption) var minIngredientWidth: CGFloat = 68
    @State private var showingFilters = false
    @State private var showingAllIngredients = false
    @State private var cooking = false
    @State private var visible = true
    @State private var sceneStatus = CloudKitchenSceneStatus.loading
    @State private var needsIngredients = false
    @AppStorage("cook.kitchen.rotationEnabled") private var allowsRotation = false

    private var background: Color {
        colorScheme == .dark ? Color(red: 0.10, green: 0.16, blue: 0.24) : Color(red: 0.93, green: 0.97, blue: 1)
    }

    private var ingredients: [StuffItem] {
        let names = CloudKitchenAsset.bundled?.ingredients.map(\.name) ?? []
        let featured = names.compactMap { name in store.stuffItems.first { $0.name == name } }
        return showingAllIngredients ? store.stuffItems : featured
    }

    var body: some View {
        let count = session.candidates(in: store).count
        GeometryReader { geometry in
            List {
                Section {
                    CookQueryField(text: $input, message: inputMessage, submit: submitInput, preview: previewInput)
                    CookActiveConditions()
                    if needsIngredients {
                        Text("这些条件暂时没找到菜，调整一下再试试。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Section {
                    VStack(spacing: 0) {
                        kitchenScene
                            .frame(height: min(380, max(230, geometry.size.width * 0.68)))

                        rotationControls
                            .padding(.horizontal, 16)
                            .padding(.bottom, 12)

                        ingredientTray
                            .padding(.horizontal, 16)
                            .padding(.bottom, 12)
                    }
                    .frame(maxWidth: 720)
                    .frame(maxWidth: .infinity)
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)

                CookInspirationSection(decide: decide, isDetailPresented: isDetailPresented)
            }
            .listSectionSpacing(.compact)
            .contentMargins(.top, 8, for: .scrollContent)
            .scrollDismissesKeyboard(.interactively)
            .scrollContentBackground(.hidden)
            .background(background)
        }
        .navigationTitle("找菜")
        .navigationBarTitleDisplayMode(.inline)
        .bottomActionBar(background: background) {
            Button(action: cook) {
                HStack(spacing: 8) {
                    Image(systemName: "frying.pan.fill")
                    Text(cooking ? "咕嘟咕嘟，菜单出炉…" : "查看全部 \(count) 道菜")
                        .contentTransition(.numericText())
                }
                .font(.headline)
                .frame(maxWidth: .infinity)
            }
            .prominentButtonStyle()
            .controlSize(.large)
            .disabled(count == 0 || cooking)
            .accessibilityIdentifier("kitchen.cook")
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { showingFilters = true } label: {
                    Label("筛选", systemImage: session.activeFilterCount > 0 ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                }
                .accessibilityValue(session.dietaryGoal.map { "饮食目标：\($0.title)" } ?? "")
                .accessibilityIdentifier("cook.filters")
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("清空") { session.clearStuff() }
                    .disabled(!session.hasSelection)
                CookPresentationToggle(selection: $presentation, usesCurrentSubtitle: dynamicTypeSize.isAccessibilitySize)
            }
        }
        .sheet(isPresented: $showingFilters) {
            FilterSheet()
        }
        .task(id: cooking) {
            guard cooking else { return }
            if !reduceMotion { try? await Task.sleep(for: .milliseconds(1200)) }
            guard !Task.isCancelled else { return }
            cooking = false
            guard !session.candidates(in: store).isEmpty else { return }
            showResults()
        }
        .onChange(of: session.selectedStuff) {
            needsIngredients = false
            cooking = false
        }
        .onAppear { visible = true }
        .onDisappear {
            visible = false
            cooking = false
            sceneStatus = .loading
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background {
                sceneStatus = .loading
                cooking = false
            }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: cooking)
    }

    private var kitchenScene: some View {
        ZStack {
            if visible, scenePhase != .background {
                CloudKitchenSceneView(
                    selected: session.selectedStuff,
                    cooking: cooking,
                    dark: colorScheme == .dark,
                    reduceMotion: reduceMotion,
                    allowsRotation: allowsRotation,
                    onSelect: session.toggleStuff,
                    onCook: cook,
                    onStatus: { sceneStatus = $0 }
                )
            }
            if sceneStatus == .loading {
                ProgressView("正在布置云厨房…")
                    .font(.caption)
                    .allowsHitTesting(false)
            } else if sceneStatus == .failed {
                VStack(spacing: 10) {
                    Image(systemName: "cloud").font(.largeTitle)
                    Text("厨房暂时没能打开")
                    Text("可以继续用下方食材和开火按钮找菜。")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .multilineTextAlignment(.center)
            }
        }
    }

    @ViewBuilder
    private var rotationControls: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                rotationInstruction
                rotationButton
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(spacing: 8) {
                rotationInstruction
                Spacer(minLength: 8)
                rotationButton
            }
        }
    }

    private var rotationInstruction: some View {
        Text(allowsRotation ? "拖动旋转，点食材选择。" : "点台面食材即可选择。")
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var rotationButton: some View {
        Button {
            allowsRotation.toggle()
        } label: {
            Label("旋转视角", systemImage: "rotate.3d")
                .fixedSize(horizontal: false, vertical: true)
        }
        .buttonStyle(.bordered)
        .tint(allowsRotation ? Color.accentColor : .secondary)
        .controlSize(dynamicTypeSize.isAccessibilitySize ? .regular : .small)
        .accessibilityIdentifier("kitchen.rotation")
        .accessibilityValue(allowsRotation ? "已开启" : "已关闭")
        .accessibilityAddTraits(allowsRotation ? .isSelected : [])
        .accessibilityHint("开启后可拖动厨房，关闭后恢复初始视角")
        .disabled(sceneStatus != .ready || cooking)
    }

    @ViewBuilder
    private var ingredientTrayHeader: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                ingredientTrayTitle
                ingredientSelectionCount
                allIngredientsButton
            }
        } else {
            HStack {
                ingredientTrayTitle
                ingredientSelectionCount
                Spacer()
                allIngredientsButton
            }
        }
    }

    private var ingredientTrayTitle: some View {
        Text("食材篮")
            .font(.headline)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var ingredientSelectionCount: some View {
        Text("已选 \(session.selectedStuff.count) 样")
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("kitchen.selectionCount")
    }

    private var allIngredientsButton: some View {
        Button(showingAllIngredients ? "收起" : "全部食材") {
            showingAllIngredients.toggle()
        }
        .font(.subheadline)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var ingredientTray: some View {
        VStack(alignment: .leading, spacing: 14) {
            ingredientTrayHeader
            LazyVGrid(columns: [GridItem(.adaptive(minimum: minIngredientWidth), spacing: 8)], spacing: 8) {
                ForEach(ingredients, id: \.name) { item in
                    KitchenIngredientButton(item: item, selected: session.selectedStuff.contains(item.name)) {
                        session.toggleStuff(item.name)
                    }
                }
            }
            .id(dynamicTypeSize)
            let extra = session.selectedStuff.filter { name in !ingredients.contains { $0.name == name } }
            if !extra.isEmpty {
                Text("也选了：\(extra.joined(separator: "、"))")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let tool = session.tool {
                Text("厨具：\(store.toolLabel(tool))").font(.caption).foregroundStyle(.secondary)
            }
            let warnings = findIncompatibleRules(session.selectedStuff, rules: store.data.incompatibleRules)
            if !warnings.isEmpty { IncompatibleWarning(rules: warnings) }
        }
        .padding(16)
        .background(colorScheme == .dark ? Color(uiColor: .secondarySystemGroupedBackground) : .white, in: RoundedRectangle(cornerRadius: 22))
    }

    private func cook() {
        guard !session.candidates(in: store).isEmpty else {
            needsIngredients = true
            return
        }
        guard !cooking else { return }
        cooking = true
    }
}

private struct KitchenIngredientButton: View {
    let item: StuffItem
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                if let icon = CloudKitchenAsset.icon(for: item.name) {
                    Image(icon).resizable().scaledToFit().frame(width: 48, height: 38).accessibilityHidden(true)
                } else {
                    Text(item.emoji).font(.system(size: 25)).accessibilityHidden(true)
                }
                Text(item.name)
                    .font(.caption)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, minHeight: 62)
            .foregroundStyle(selected ? Color.accentColor : .primary)
            .background(selected ? Color.accentColor.opacity(0.12) : Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: 13))
            .overlay(alignment: .topTrailing) {
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 12)).foregroundStyle(Color.accentColor)
                        .padding(4).accessibilityHidden(true)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.name)
        .accessibilityIdentifier("kitchen.ingredient.\(item.name)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
