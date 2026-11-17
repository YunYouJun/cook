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
    // SwiftUI measures the fraction against the maximum detent, not the entire screen.
    private static let compactDetent = PresentationDetent.fraction(0.71)
    private static let compactCardMinHeight: CGFloat = 204
    // Native large button styles add vertical padding around the label.
    private static let actionLabelMinHeight: CGFloat = 24

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

    @State private var pages = DecidePages()
    @State private var activePool: [Recipe] = []
    @State private var rollingRecipe: Recipe?
    @State private var shuffling = false
    @State private var spin = 0
    @State private var settledCount = 0
    @State private var rollToken = 0
    @State private var showingFilters = false
    @State private var detent = CookDecideSheet.compactDetent
    @State private var pageDirection = DecidePageDirection.next
    @State private var dragDistance: CGFloat = 0

    private var displayed: Recipe? { rollingRecipe ?? pages.current }
    private var canReroll: Bool { activePool.count > 1 }

    private var isCompact: Bool {
        !dynamicTypeSize.isAccessibilitySize && detent == Self.compactDetent
    }

    private struct RollTaskID: Hashable {
        let pool: [Recipe]
        let revision: Int
    }

    var body: some View {
        let snapshot = session.candidateSnapshot(in: store, ignoringKeyword: request.ignoringKeyword)
        let pool = resolvedPool(snapshot)
        let lines = CookDiscoverySummary.lines(session: session, store: store, ignoringKeyword: request.ignoringKeyword)
        let context = lines.isEmpty ? CookDiscoverySummary.idlePrompt(session: session) : lines.joined(separator: " · ")
        NavigationStack {
            VStack(spacing: 14) {
                results(snapshot, context: context, hasCandidates: !pool.isEmpty)
                if !pool.isEmpty {
                    actions(canReroll: pool.count > 1)
                }
            }
            .navigationTitle("今天吃什么")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: {
                        Label("关闭", systemImage: "xmark")
                            .labelStyle(.iconOnly)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel("关闭")
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
        .presentationDetents(
            dynamicTypeSize.isAccessibilitySize ? [.large] : [Self.compactDetent, .large],
            selection: dynamicTypeSize.isAccessibilitySize ? .constant(.large) : $detent
        )
        .presentationContentInteraction(.scrolls)
        .presentationDragIndicator(.visible)
        .presentationBackground(Color(uiColor: .systemGroupedBackground))
        .sheet(isPresented: $showingFilters) { FilterSheet() }
        .task(id: RollTaskID(pool: pool, revision: rollToken)) { await roll(in: pool) }
        .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
            guard scenePhase == .active else { return }
            requestReroll()
        }
        .sensoryFeedback(.success, trigger: settledCount)
    }

    private func results(_ snapshot: CookCandidateSnapshot, context: String, hasCandidates: Bool) -> some View {
        let fillsViewport = isCompact && hasCandidates
        let topPadding: CGFloat = 8
        return GeometryReader { viewport in
            ScrollView {
                DecideResultsLayout(minimumHeight: fillsViewport ? max(0, viewport.size.height - topPadding) : 0) {
                    Text(context)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(isCompact ? 2 : nil)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("cook.decide.context")
                    if hasCandidates { stage(snapshot) } else { emptyState }
                }
                .padding(.horizontal, 20)
                .padding(.top, topPadding)
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityIdentifier("cook.decide.sheet")
        }
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
                if isCompact {
                    HStack(spacing: 8) {
                        die
                        stageTitle
                    }
                    .frame(maxWidth: .infinity)
                    .overlay(alignment: .trailing) { pageIndicator }
                } else {
                    stageTitle
                        .frame(maxWidth: .infinity)
                        .overlay(alignment: .trailing) { pageIndicator }
                    die
                }
            } else {
                pageIndicator.frame(maxWidth: .infinity, alignment: .trailing)
            }
            ZStack {
                if let recipe = displayed, resolvedPool(snapshot).contains(where: { $0.id == recipe.id }) {
                    recipeCard(recipe, snapshot: snapshot)
                        .id(recipe.id)
                        .transition(cardTransition)
                }
            }
            .rotationEffect(.degrees(Double(dragOffset) / 22))
            .offset(x: dragOffset)
            .frame(maxWidth: .infinity, minHeight: isCompact ? Self.compactCardMinHeight : 220)
            .background {
                if !reduceMotion, !dynamicTypeSize.isAccessibilitySize {
                    DecideDeck(shuffling: shuffling)
                }
            }
            .overlay {
                DecideCardPan(isEnabled: !shuffling && pages.current != nil && !showingFilters) { distance in
                    if !reduceMotion { dragDistance = distance }
                } onFinish: { direction in
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { dragDistance = 0 }
                    if let direction { turnPage(direction) }
                }
                .accessibilityHidden(true)
            }
            .padding(.top, reduceMotion ? 0 : 8)
            .padding(.bottom, isCompact ? 4 : (reduceMotion ? 0 : 8))
        }
    }

    @ViewBuilder
    private var pageIndicator: some View {
        if !pages.isEmpty {
            Text("\(pages.number) / \(pages.count)")
                .font(.caption2.monospacedDigit())
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
    }

    private var dragOffset: CGFloat {
        guard !reduceMotion, !shuffling else { return 0 }
        let canMove = dragDistance > 0 ? pages.hasPrevious : pages.hasNext || canReroll
        let travel = dragDistance * (canMove ? 0.74 : 0.2)
        return min(116, max(-116, travel))
    }

    private var stageTitle: some View {
        Text(shuffling ? "正在挑一道菜" : "就做这道")
            .font(.headline)
            .accessibilityHidden(true)
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
        .font(.system(size: isCompact ? dieSize * 2 / 3 : dieSize))
        .foregroundStyle(Color.accentColor)
        .frame(minWidth: 44, minHeight: 44)
        .accessibilityHidden(true)
    }

    private var cardTransition: AnyTransition {
        if reduceMotion { return .opacity }
        let identity = DecideCardMotion(offset: 0, angle: 0, opacity: 1, scale: 1)
        let direction: CGFloat = pageDirection == .next ? 1 : -1
        let insertion = AnyTransition.modifier(
            active: DecideCardMotion(offset: 46 * direction, angle: 8 * Double(direction), opacity: 0, scale: 0.92),
            identity: identity
        )
        let removal = AnyTransition.modifier(
            active: DecideCardMotion(offset: -40 * direction, angle: -7 * Double(direction), opacity: 0, scale: 1.04),
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
        .frame(maxWidth: .infinity, minHeight: isCompact ? Self.compactCardMinHeight : nil)
        .background(.background, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(.quaternary, lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isStaticText)
        .accessibilityLabel(shuffling ? "正在挑选" : accessibilitySummary(recipe, match: match))
        .accessibilityValue(shuffling ? "" : "第 \(pages.number) 道，共 \(pages.count) 道")
        .accessibilityActions {
            if !shuffling, pages.hasPrevious { Button("上一道") { turnPage(.previous) } }
            if !shuffling, pages.hasNext || canReroll { Button("下一道") { turnPage(.next) } }
        }
        .accessibilityIdentifier("cook.decide.recipe")
    }

    private func actions(canReroll: Bool) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                rerollButton(enabled: canReroll, wrapping: false)
                openButton(wrapping: false)
            }
            VStack(spacing: 8) {
                openButton(wrapping: true)
                rerollButton(enabled: canReroll, wrapping: true)
            }
        }
        .font(.headline)
        .multilineTextAlignment(.center)
        .controlSize(.large)
        .padding(.horizontal, 20)
        .padding(.bottom, 12)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func openButton(wrapping: Bool) -> some View {
        Button(action: confirm) {
            Text("看做法")
                .fixedSize(horizontal: !wrapping, vertical: true)
                .frame(maxWidth: .infinity, minHeight: Self.actionLabelMinHeight)
        }
        .prominentButtonStyle()
        .disabled(displayed == nil || shuffling)
        .accessibilityHint("打开这道菜的做法")
        .accessibilityIdentifier("cook.decide.open")
    }

    private func rerollButton(enabled: Bool, wrapping: Bool) -> some View {
        Button(action: requestReroll) {
            Label("换一道", systemImage: "arrow.triangle.2.circlepath")
                .fixedSize(horizontal: !wrapping, vertical: true)
                .frame(maxWidth: .infinity, minHeight: Self.actionLabelMinHeight)
        }
        .buttonStyle(.bordered)
        .disabled(!enabled || shuffling)
        .accessibilityHint("按当前条件再选一道")
        .accessibilityIdentifier("cook.decide.again")
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

    private func requestReroll() {
        guard !shuffling, !showingFilters, canReroll, pages.current != nil else { return }
        pageDirection = .next
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.22)) { shuffling = true }
        rollToken += 1
    }

    private func turnPage(_ direction: DecidePageDirection) {
        guard !shuffling, !showingFilters, pages.current != nil else { return }
        if direction == .next, !pages.hasNext {
            requestReroll()
            return
        }
        guard direction == .next || pages.hasPrevious else { return }
        pageDirection = direction
        withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .snappy(duration: 0.28)) {
            pages.move(direction)
        }
        if let recipe = pages.current {
            AccessibilityNotification.Announcement("\(recipe.name)，第 \(pages.number) 道，共 \(pages.count) 道").post()
        }
    }

    private func roll(in pool: [Recipe]) async {
        guard !Task.isCancelled else { return }
        if pool != activePool {
            // A new candidate pool starts a fresh deck; earlier pages may no longer match.
            activePool = pool
            pages = DecidePages()
            rollingRecipe = nil
            shuffling = false
        }
        pageDirection = .next
        let previousID = pages.current?.id ?? lastDecidedID
        let previousName = pages.current?.name ?? pool.first { $0.id == previousID }?.name
        let alternatives = pool.filter { $0.name != previousName }
        guard let landing = store.randomRecipe(from: alternatives.isEmpty ? pool : alternatives, excluding: previousID) else {
            shuffling = false
            rollingRecipe = nil
            AccessibilityNotification.Announcement("这次还没找到合适的菜").post()
            return
        }
        if pages.isEmpty || reduceMotion || pool.count < 2 {
            settle(landing, animation: reduceMotion ? .easeOut(duration: 0.12) : .spring(response: 0.42, dampingFraction: 0.72))
            return
        }

        shuffling = true
        try? await Task.sleep(for: .milliseconds(160))
        if Task.isCancelled { return }
        let reel = CookDiscovery.decideReel(in: pool, landing: landing)
        for (index, recipe) in reel.enumerated() {
            if Task.isCancelled { return }
            if index == reel.count - 1 {
                settle(landing, animation: .spring(response: 0.46, dampingFraction: 0.68))
                return
            }
            withAnimation(.snappy(duration: 0.16)) {
                rollingRecipe = recipe
                spin += 1
            }
            try? await Task.sleep(for: .milliseconds(110))
            if Task.isCancelled { return }
        }
    }

    private func settle(_ recipe: Recipe, animation: Animation) {
        withAnimation(animation) {
            pages.append(recipe)
            rollingRecipe = nil
            shuffling = false
        }
        lastDecidedID = recipe.id
        settledCount += 1
        AccessibilityNotification.Announcement("就做\(recipe.name)").post()
    }
}

private enum DecidePageDirection {
    case previous, next
}

/// Owned by one sheet. The transient recipes shown while shuffling never become pages.
private struct DecidePages {
    private var recipes: [Recipe] = []
    private var index = 0

    var count: Int { recipes.count }
    var isEmpty: Bool { recipes.isEmpty }
    var number: Int { isEmpty ? 0 : index + 1 }
    var current: Recipe? { isEmpty ? nil : recipes[index] }
    var hasPrevious: Bool { index > 0 }
    var hasNext: Bool { index + 1 < count }

    mutating func append(_ recipe: Recipe) {
        if recipes.last?.id != recipe.id { recipes.append(recipe) }
        index = recipes.count - 1
    }

    mutating func move(_ direction: DecidePageDirection) {
        switch direction {
        case .previous where hasPrevious: index -= 1
        case .next where hasNext: index += 1
        default: break
        }
    }
}

/// Reject a vertical pan before recognition so the enclosing scroll view can take it.
private struct DecideCardPan: UIViewRepresentable {
    let isEnabled: Bool
    let onMove: (CGFloat) -> Void
    let onFinish: (DecidePageDirection?) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pan(_:)))
        pan.delegate = context.coordinator
        pan.maximumNumberOfTouches = 1
        view.addGestureRecognizer(pan)
        return view
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func updateUIView(_ view: UIView, context: Context) {
        context.coordinator.configuration = self
    }

    final class Coordinator: NSObject, UIGestureRecognizerDelegate {
        var configuration: DecideCardPan

        init(_ configuration: DecideCardPan) { self.configuration = configuration }

        func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
            guard configuration.isEnabled, let pan = gestureRecognizer as? UIPanGestureRecognizer else { return false }
            let velocity = pan.velocity(in: nil)
            return abs(velocity.x) > abs(velocity.y) * 1.35
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldBeRequiredToFailBy other: UIGestureRecognizer) -> Bool {
            (other.view as? UIScrollView)?.panGestureRecognizer === other
        }

        @objc func pan(_ gesture: UIPanGestureRecognizer) {
            let translation = gesture.translation(in: nil)
            switch gesture.state {
            case .began, .changed:
                configuration.onMove(translation.x)
            case .ended:
                guard abs(translation.x) >= 48, abs(translation.x) > abs(translation.y) * 1.35 else {
                    configuration.onFinish(nil)
                    return
                }
                configuration.onFinish(translation.x > 0 ? .previous : .next)
            case .cancelled, .failed:
                configuration.onFinish(nil)
            default:
                break
            }
        }
    }
}

/// Gives a short result the bottom of the scroll viewport, while long results can scroll naturally.
private struct DecideResultsLayout: Layout {
    let minimumHeight: CGFloat
    private let spacing: CGFloat = 16

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(ProposedViewSize(width: proposal.width, height: nil)) }
        return CGSize(
            width: proposal.width ?? sizes.map(\.width).max() ?? 0,
            height: max(minimumHeight, contentHeight(sizes))
        )
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let childProposal = ProposedViewSize(width: bounds.width, height: nil)
        let sizes = subviews.map { $0.sizeThatFits(childProposal) }
        let extra = max(0, bounds.height - contentHeight(sizes))
        let breathingRoom = extra / CGFloat(max(1, subviews.count))
        var y = bounds.minY + breathingRoom
        for (view, size) in zip(subviews, sizes) {
            view.place(at: CGPoint(x: bounds.minX, y: y), anchor: .topLeading, proposal: childProposal)
            y += size.height + spacing + breathingRoom
        }
    }

    private func contentHeight(_ sizes: [CGSize]) -> CGFloat {
        sizes.reduce(0) { $0 + $1.height } + spacing * CGFloat(max(0, sizes.count - 1))
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
        .opacity(shuffling ? 1 : 0)
        .animation(.easeOut(duration: 0.22), value: shuffling)
        .accessibilityHidden(true)
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
