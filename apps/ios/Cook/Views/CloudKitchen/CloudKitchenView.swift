import CookCore
import SwiftUI

/// A second presentation of CookSession. All matching, filters and destinations
/// are the same ones used by the ingredient list.
struct CloudKitchenView: View {
    @Binding var presentation: CookPresentation
    let showResults: () -> Void
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var showingFilters = false
    @State private var showingAllIngredients = false
    @State private var cooking = false
    @State private var visible = true
    @State private var sceneStatus = CloudKitchenSceneStatus.loading
    @State private var needsIngredients = false

    private var background: Color {
        colorScheme == .dark ? Color(red: 0.10, green: 0.16, blue: 0.24) : Color(red: 0.93, green: 0.97, blue: 1)
    }

    private var ingredients: [StuffItem] {
        let names = CloudKitchenAsset.bundled?.ingredients.map(\.name) ?? []
        let featured = names.compactMap { name in store.stuffItems.first { $0.name == name } }
        return showingAllIngredients ? store.stuffItems : featured
    }

    var body: some View {
        let count = session.matchCount(using: store.recommender) ?? 0
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 0) {
                    VStack(spacing: 7) {
                        Text("今晚，做点好吃的。")
                            .font(.system(.title2, design: .rounded, weight: .bold))
                        Text(needsIngredients ? "先选几样食材，再点锅开火。" : "点台面上的食材，选好后点锅开火。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 12)

                    kitchenScene
                        .frame(height: min(430, max(250, geometry.size.width * 0.82)))

                    ingredientTray
                        .padding(.horizontal, 20)
                        .padding(.bottom, 20)
                }
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(background)
        }
        .navigationTitle("做菜")
        .navigationBarTitleDisplayMode(.inline)
        .bottomActionBar {
            Button(action: cook) {
                HStack(spacing: 8) {
                    Image(systemName: "frying.pan.fill")
                    Text(cooking ? "咕嘟咕嘟，菜单出炉…" : session.hasSelection ? "开火，看看 \(count) 道菜" : "先选几样食材")
                        .contentTransition(.numericText())
                }
                .font(.headline)
                .frame(maxWidth: .infinity)
            }
            .prominentButtonStyle()
            .controlSize(.large)
            .disabled(!session.hasSelection || cooking)
            .accessibilityIdentifier("kitchen.cook")
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { showingFilters = true } label: {
                    Label("筛选", systemImage: session.activeFilterCount > 0 ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("清空") { session.clearStuff() }
                    .disabled(!session.hasSelection)
                CookPresentationToggle(selection: $presentation)
            }
        }
        .sheet(isPresented: $showingFilters) {
            FilterSheet()
                .presentationDetents([.medium, .large])
                .presentationBackground(Color(uiColor: .systemGroupedBackground))
        }
        .task(id: cooking) {
            guard cooking else { return }
            if !reduceMotion { try? await Task.sleep(for: .milliseconds(1200)) }
            guard !Task.isCancelled, session.hasSelection else { return }
            cooking = false
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
        }
        .sensoryFeedback(.impact(weight: .light), trigger: cooking)
    }

    private var kitchenScene: some View {
        ZStack {
            if visible, scenePhase == .active {
                CloudKitchenSceneView(
                    selected: session.selectedStuff,
                    cooking: cooking,
                    dark: colorScheme == .dark,
                    reduceMotion: reduceMotion,
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

    private var ingredientTray: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("食材篮").font(.headline)
                Text("已选 \(session.selectedStuff.count) 样")
                    .font(.caption).foregroundStyle(.secondary)
                    .accessibilityIdentifier("kitchen.selectionCount")
                Spacer()
                Button(showingAllIngredients ? "收起" : "全部食材") {
                    showingAllIngredients.toggle()
                }
                .font(.subheadline)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 68), spacing: 8)], spacing: 8) {
                ForEach(ingredients, id: \.name) { item in
                    KitchenIngredientButton(item: item, selected: session.selectedStuff.contains(item.name)) {
                        session.toggleStuff(item.name)
                    }
                }
            }
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
        guard session.hasSelection else {
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
                Text(item.name).font(.caption).lineLimit(1).minimumScaleFactor(0.75)
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
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
