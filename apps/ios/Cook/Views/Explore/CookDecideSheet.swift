import CookCore
import SwiftUI
import UIKit

/// 本次随机决定从哪一批候选里抽。`batchIDs == nil` 表示当前会话的全部候选。
struct CookDecideRequest: Identifiable {
    let id = UUID()
    let batchIDs: [Int]?
    var ignoringKeyword = false

    /// A captured empty batch stays empty instead of broadening to the catalog.
    func recipes(in snapshot: CookCandidateSnapshot) -> [Recipe] {
        guard let batchIDs else { return snapshot.recipes }
        let ids = Set(batchIDs)
        return snapshot.recipes.filter { ids.contains($0.id) }
    }
}

extension View {
    func cookDecideSheet(
        request: Binding<CookDecideRequest?>,
        lastDecidedID: Binding<Int?>,
        openRecipe: @escaping (RecipeRoute) -> Void
    ) -> some View {
        modifier(CookDecideSheetModifier(request: request, lastDecidedID: lastDecidedID, openRecipe: openRecipe))
    }
}

/// Conditions shared by the inspiration section and the library decision sheet.
enum CookDiscoverySummary {
    static func lines(session: CookSession, store: RecipeStore, ignoringKeyword: Bool = false) -> [String] {
        var parts: [String] = []
        if let goal = session.dietaryGoal { parts.append("饮食目标：\(goal.title)") }
        if !session.selectedStuff.isEmpty {
            parts.append("已有食材：\(session.selectedStuff.joined(separator: "、"))")
            parts.append("推荐方式：\(session.mode.displayTitle)")
        }
        if let tool = session.tool { parts.append("厨具：\(store.toolLabel(tool))") }
        let keyword = session.keyword.trimmingCharacters(in: .whitespacesAndNewlines)
        if !ignoringKeyword, !keyword.isEmpty { parts.append("菜名：\(keyword)") }
        if let scene = session.discoveryScene { parts.append("想法：\(scene.label)") }
        if !session.avoid.isEmpty { parts.append("忌口：\(session.avoid.joined(separator: "、"))") }
        if session.noSpicy { parts.append("不吃辣") }
        parts += session.methodGroups + session.difficulty
        return parts
    }

    static func emptyExplanation(session: CookSession, store: RecipeStore, ignoringKeyword: Bool = false) -> String {
        if let goal = session.dietaryGoal, let message = store.dietaryUnavailableMessage(for: goal) { return message }
        if session.discoveryScene != nil {
            return "当前条件和这次的想法暂时没有交集，可以取消场景，或调整食材、厨具和筛选。"
        }
        if !ignoringKeyword, !session.keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "换个菜名试试，或清除菜名继续看看。已有食材、忌口和饮食目标会继续保留。"
        }
        return "还没找到符合这些条件的菜，试试调整食材、厨具或筛选。"
    }

    /// 还没有任何本次条件时，说明抽的是整本菜谱，不暗示家里食材已经齐了。
    static func idlePrompt(session: CookSession) -> String {
        session.selectedStuff.isEmpty ? "还没选食材。会按当前筛选，从菜谱里挑一道。" : "从当前能做的菜里挑一道。"
    }
}

private struct CookDecideSheetModifier: ViewModifier {
    @Binding var request: CookDecideRequest?
    @Binding var lastDecidedID: Int?
    let openRecipe: (RecipeRoute) -> Void
    @State private var pending: RecipeRoute?

    func body(content: Content) -> some View {
        content.sheet(item: $request, onDismiss: {
            guard let route = pending else { return }
            pending = nil
            openRecipe(route)
        }) { item in
            CookDecideSheet(request: item, lastDecidedID: $lastDecidedID) { route in
                pending = route
                request = nil
            }
        }
    }
}

/// 按当前候选快照选一道菜。先换牌，确认后再进入既有详情。
private struct CookDecideSheet: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.scenePhase) private var scenePhase
    @ScaledMetric(relativeTo: .largeTitle) private var dieSize: CGFloat = 48
    @ScaledMetric(relativeTo: .largeTitle) private var thumbnailSize: CGFloat = 64

    let request: CookDecideRequest
    @Binding var lastDecidedID: Int?
    let onOpen: (RecipeRoute) -> Void

    @State private var displayed: Recipe?
    @State private var shuffling = false
    @State private var spin = 0
    @State private var settledCount = 0
    @State private var rollToken = 0
    @State private var showingFilters = false
    @State private var detent: PresentationDetent = .large

    private struct RollTaskID: Hashable {
        let pool: [Recipe]
        let revision: Int
    }

    var body: some View {
        let snapshot = session.candidateSnapshot(in: store, ignoringKeyword: request.ignoringKeyword)
        let pool = resolvedPool(snapshot)
        let lines = CookDiscoverySummary.lines(session: session, store: store, ignoringKeyword: request.ignoringKeyword)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(lines.isEmpty ? CookDiscoverySummary.idlePrompt(session: session) : lines.joined(separator: " · "))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("cook.decide.context")
                    if pool.isEmpty {
                        emptyState
                    } else {
                        stage(snapshot)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 12)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityIdentifier("cook.decide.sheet")
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if !pool.isEmpty {
                    actions(pool: pool)
                }
            }
            .navigationTitle("今天吃什么")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                        .accessibilityIdentifier("cook.decide.close")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showingFilters = true } label: {
                        Label("筛选", systemImage: session.activeFilterCount > 0 ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                    }
                    .accessibilityValue(session.dietaryGoal?.title ?? "不限目标")
                    .accessibilityIdentifier("cook.decide.filters")
                }
            }
        }
        .presentationDetents(dynamicTypeSize.isAccessibilitySize ? [.large] : [.medium, .large], selection: $detent)
        .presentationDragIndicator(.visible)
        .presentationBackground(Color(uiColor: .systemGroupedBackground))
        .sheet(isPresented: $showingFilters) { FilterSheet() }
        .task(id: RollTaskID(pool: pool, revision: rollToken)) { await roll(in: pool) }
        .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
            guard scenePhase == .active, !showingFilters else { return }
            rollToken += 1
        }
        .sensoryFeedback(.success, trigger: settledCount)
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("这次还没找到合适的菜。")
                .font(.headline)
                .accessibilityIdentifier("cook.decide.empty")
            Text(CookDiscoverySummary.emptyExplanation(session: session, store: store, ignoringKeyword: request.ignoringKeyword))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if session.discoveryScene != nil {
                Button("取消这次场景") { session.discoveryScene = nil }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("cook.decide.reset")
            }
        }
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func stage(_ snapshot: CookCandidateSnapshot) -> some View {
        VStack(spacing: 14) {
            if !dynamicTypeSize.isAccessibilitySize {
                Text(shuffling ? "正在挑一道菜" : "就做这道")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .accessibilityHidden(true)
                die
            }
            ZStack {
                if !reduceMotion, !dynamicTypeSize.isAccessibilitySize {
                    DecideDeck(shuffling: shuffling)
                }
                if let recipe = displayed, resolvedPool(snapshot).contains(where: { $0.id == recipe.id }) {
                    recipeCard(recipe, snapshot: snapshot)
                        .id(recipe.id)
                        .transition(cardTransition)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 220)
            .padding(.vertical, reduceMotion ? 0 : 8)
        }
    }

    private var die: some View {
        let symbol = reduceMotion || !shuffling ? "dice.fill" : "die.face.\((abs(spin) % 6) + 1)"
        return Group {
            if reduceMotion {
                Image(systemName: "dice.fill")
            } else {
                Image(systemName: symbol)
                    .contentTransition(.symbolEffect(.replace))
                    .symbolEffect(.bounce, value: settledCount)
            }
        }
        .font(.system(size: dieSize))
        .foregroundStyle(Color.accentColor)
        .frame(minWidth: 44, minHeight: 44)
        .accessibilityHidden(true)
    }

    private var cardTransition: AnyTransition {
        if reduceMotion { return .opacity }
        let identity = DecideCardMotion(offset: 0, angle: 0, opacity: 1, scale: 1)
        let insertion = AnyTransition.modifier(
            active: DecideCardMotion(offset: 46, angle: 8, opacity: 0, scale: 0.92),
            identity: identity
        )
        let removal = AnyTransition.modifier(
            active: DecideCardMotion(offset: -40, angle: -7, opacity: 0, scale: 1.04),
            identity: identity
        )
        return .asymmetric(insertion: insertion, removal: removal)
    }

    private func recipeCard(_ recipe: Recipe, snapshot: CookCandidateSnapshot) -> some View {
        let match = snapshot.matchesByID[recipe.id]
        return VStack(spacing: 10) {
            if !dynamicTypeSize.isAccessibilitySize {
                Text(store.thumbnail(for: recipe))
                    .font(.system(size: thumbnailSize))
                    .accessibilityHidden(true)
            }
            Text(recipe.name)
                .font(.title.bold())
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Text(store.meta(for: recipe))
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if let match {
                if match.missingStuff.isEmpty {
                    Text("所需食材已齐")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    Text("还缺 \(match.missingStuff.joined(separator: "、"))")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let goal = session.dietaryGoal, let entry = store.dietaryCatalog?.entry(for: recipe, goal: goal) {
                Text(entry.reason(for: goal))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let scene = session.discoveryScene {
                Text(CookDiscovery.reason(for: recipe, filters: scene.filters))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity)
        .background(.background, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(.quaternary, lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(shuffling ? "正在挑选" : accessibilitySummary(recipe, match: match))
        .accessibilityIdentifier("cook.decide.recipe")
    }

    private func actions(pool: [Recipe]) -> some View {
        VStack(spacing: 8) {
            Button {
                confirm()
            } label: {
                Text("看做法")
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .prominentButtonStyle()
            .controlSize(.large)
            .disabled(displayed == nil || shuffling)
            .accessibilityHint("打开这道菜的做法")
            .accessibilityIdentifier("cook.decide.open")

            Button(action: { rollToken += 1 }) {
                Label("换一道", systemImage: "arrow.triangle.2.circlepath")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .disabled(shuffling || pool.count < 2)
            .accessibilityHint("按当前条件再选一道")
            .accessibilityIdentifier("cook.decide.again")
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .background(Color(uiColor: .systemGroupedBackground))
    }

    private func resolvedPool(_ snapshot: CookCandidateSnapshot) -> [Recipe] {
        request.recipes(in: snapshot)
    }

    private func accessibilitySummary(_ recipe: Recipe, match: RecipeMatch?) -> String {
        var parts = [recipe.name, store.meta(for: recipe)]
        if let match {
            parts.append(match.missingStuff.isEmpty ? "所需食材已齐" : "还缺\(match.missingStuff.joined(separator: "、"))")
        }
        if let goal = session.dietaryGoal, let reason = store.dietaryCatalog?.entry(for: recipe, goal: goal)?.reason(for: goal) {
            parts.append(reason)
        }
        if let scene = session.discoveryScene {
            parts.append(CookDiscovery.reason(for: recipe, filters: scene.filters))
        }
        return parts.joined(separator: "，")
    }

    private func confirm() {
        guard let displayed, !shuffling else { return }
        let current = session.candidateSnapshot(in: store, ignoringKeyword: request.ignoringKeyword)
        guard let recipe = resolvedPool(current).first(where: { $0.id == displayed.id }) else {
            rollToken += 1
            return
        }
        onOpen(RecipeRoute(recipe: recipe, match: current.matchesByID[recipe.id], dietaryGoal: session.dietaryGoal))
    }

    private func roll(in pool: [Recipe]) async {
        guard !Task.isCancelled else { return }
        let previousName = pool.first { $0.id == lastDecidedID }?.name
        let alternatives = pool.filter { $0.name != previousName }
        guard let landing = store.randomRecipe(from: alternatives.isEmpty ? pool : alternatives, excluding: lastDecidedID) else {
            shuffling = false
            displayed = nil
            AccessibilityNotification.Announcement("这次还没找到合适的菜").post()
            return
        }
        if displayed == nil || reduceMotion || pool.count < 2 {
            lastDecidedID = landing.id
            withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .spring(response: 0.42, dampingFraction: 0.72)) {
                displayed = landing
                shuffling = false
            }
            settledCount += 1
            AccessibilityNotification.Announcement("就做\(landing.name)").post()
            return
        }

        shuffling = true
        try? await Task.sleep(for: .milliseconds(160))
        if Task.isCancelled { return }
        let reel = CookDiscovery.decideReel(in: pool, landing: landing)
        for (index, recipe) in reel.enumerated() {
            if Task.isCancelled { return }
            let isLast = index == reel.count - 1
            withAnimation(isLast ? .spring(response: 0.46, dampingFraction: 0.68) : .snappy(duration: 0.16)) {
                displayed = recipe
                spin += 1
                if isLast { shuffling = false }
            }
            if isLast {
                lastDecidedID = landing.id
                settledCount += 1
                AccessibilityNotification.Announcement("就做\(landing.name)").post()
                return
            }
            try? await Task.sleep(for: .milliseconds(110))
            if Task.isCancelled { return }
        }
    }
}

/// 洗牌时露在结果卡片后面的两张牌。减少动态效果时不显示。
private struct DecideDeck: View {
    var shuffling: Bool

    var body: some View {
        ZStack {
            plate
                .rotationEffect(.degrees(shuffling ? 7 : 3))
                .offset(x: shuffling ? 14 : 8, y: 6)
            plate
                .rotationEffect(.degrees(shuffling ? -6 : -2.5))
                .offset(x: shuffling ? -12 : -7, y: 3)
        }
        .padding(.horizontal, 8)
        .accessibilityHidden(true)
        .animation(.spring(response: 0.45, dampingFraction: 0.72), value: shuffling)
    }

    private var plate: some View {
        RoundedRectangle(cornerRadius: 22, style: .continuous)
            .fill(Color.primary.opacity(0.05))
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
            }
    }
}

/// 卡片换位。减少动态效果时不使用这个位移，只保留透明度。
private struct DecideCardMotion: ViewModifier, Animatable {
    var offset: CGFloat
    var angle: Double
    var opacity: Double
    var scale: CGFloat

    var animatableData: AnimatablePair<AnimatablePair<CGFloat, Double>, AnimatablePair<Double, CGFloat>> {
        get { AnimatablePair(AnimatablePair(offset, angle), AnimatablePair(opacity, scale)) }
        set {
            offset = newValue.first.first
            angle = newValue.first.second
            opacity = newValue.second.first
            scale = newValue.second.second
        }
    }

    func body(content: Content) -> some View {
        content
            .scaleEffect(scale)
            .rotationEffect(.degrees(angle))
            .offset(x: offset)
            .opacity(opacity)
    }
}
