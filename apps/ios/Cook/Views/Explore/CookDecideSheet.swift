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

private struct DecidePage: Identifiable, Hashable {
    let id: UUID
    let recipe: Recipe

    init(id: UUID = UUID(), recipe: Recipe) {
        self.id = id
        self.recipe = recipe
    }
}

/// 按当前候选快照选一道菜。使用原生横向分页浏览，确认后再进入既有详情。
private struct CookDecideSheet: View {
    // SwiftUI measures the fraction against the maximum detent, not the entire screen.
    private static let compactDetent = PresentationDetent.fraction(0.71)
    // Native large button styles add vertical padding around the label.
    private static let actionLabelMinHeight: CGFloat = 24
    private static let trailingBufferCount = 2

    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.scenePhase) private var scenePhase
    @ScaledMetric(relativeTo: .largeTitle) private var dieSize: CGFloat = 28
    @ScaledMetric(relativeTo: .largeTitle) private var thumbnailSize: CGFloat = 64

    let request: CookDecideRequest
    @Binding var lastDecidedID: Int?
    let onOpen: (RecipeRoute) -> Void

    @State private var activePool: [Recipe] = []
    @State private var deck: [DecidePage] = []
    @State private var revealedCount = 0
    @State private var selectedPageID: UUID?
    @State private var deckGeneration = 0
    @State private var settledCount = 0
    @State private var showingFilters = false
    @State private var detent = CookDecideSheet.compactDetent

    private var currentIndex: Int? {
        selectedPageID.flatMap { id in deck.firstIndex { $0.id == id } }
    }

    private var currentRecipe: Recipe? {
        currentIndex.map { deck[$0].recipe }
    }

    private var pageNumber: Int {
        currentIndex.map { $0 + 1 } ?? 0
    }

    private var pageTotal: Int {
        max(revealedCount, pageNumber)
    }

    private var canReroll: Bool { activePool.count > 1 }
    private var hasPrevious: Bool { (currentIndex ?? 0) > 0 }
    private var hasNext: Bool {
        guard let idx = currentIndex else { return false }
        return idx + 1 < pageTotal
    }

    var body: some View {
        let snapshot = session.candidateSnapshot(in: store, ignoringKeyword: request.ignoringKeyword)
        let pool = resolvedPool(snapshot)
        let lines = CookDiscoverySummary.lines(session: session, store: store, ignoringKeyword: request.ignoringKeyword)
        let context = lines.isEmpty ? CookDiscoverySummary.idlePrompt(session: session) : lines.joined(separator: " · ")
        NavigationStack {
            VStack(spacing: 0) {
                if pool.isEmpty {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            contextText(context)
                            emptyState
                        }
                        .padding(.horizontal, 20)
                        .padding(.top, 8)
                        .frame(maxWidth: 560)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .accessibilityIdentifier("cook.decide.sheet")
                } else {
                    VStack(spacing: 12) {
                        contextText(context)
                            .padding(.horizontal, 20)
                            .layoutPriority(1)
                        stageHeader
                            .padding(.horizontal, 20)
                            .layoutPriority(1)
                        cardPager(snapshot: snapshot)
                            .frame(
                                minHeight: 196,
                                maxHeight: dynamicTypeSize.isAccessibilitySize ? .infinity : 320
                            )
                    }
                    .padding(.top, 8)
                    .padding(.bottom, 12)
                    .frame(maxWidth: 560)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("cook.decide.sheet")

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
        .onChange(of: pool, initial: true) { _, updated in
            syncPool(updated)
        }
        .onChange(of: selectedPageID) { oldID, newID in
            handlePageSelectionChange(from: oldID, to: newID)
        }
        .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
            guard scenePhase == .active else { return }
            requestReroll()
        }
        .sensoryFeedback(.selection, trigger: settledCount)
    }

    private func contextText(_ context: String) -> some View {
        Text(context)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("cook.decide.context")
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

    @ViewBuilder
    private var stageHeader: some View {
        if !dynamicTypeSize.isAccessibilitySize {
            HStack(spacing: 8) {
                die
                Text("就做这道")
                    .font(.headline)
                    .accessibilityHidden(true)
                Spacer(minLength: 8)
                pageIndicator
            }
        } else {
            pageIndicator
                .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    @ViewBuilder
    private var pageIndicator: some View {
        if pageTotal > 0 {
            Text("\(pageNumber) / \(pageTotal)")
                .font(.footnote.monospacedDigit())
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
    }

    private var die: some View {
        Group {
            if reduceMotion {
                Image(systemName: "dice.fill")
            } else {
                Image(systemName: "dice.fill")
                    .symbolEffect(.bounce, value: settledCount)
            }
        }
        .font(.system(size: dieSize))
        .foregroundStyle(Color.accentColor)
        .frame(minWidth: 32, minHeight: 32)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func cardPager(snapshot: CookCandidateSnapshot) -> some View {
        let reduceMotion = self.reduceMotion
        if deck.isEmpty {
            Color.clear
        } else {
            ScrollView(.horizontal) {
                LazyHStack(spacing: 12) {
                    ForEach(deck) { page in
                        recipeCard(page.recipe, snapshot: snapshot, isCurrent: page.id == selectedPageID)
                            .containerRelativeFrame(.horizontal)
                            .id(page.id)
                            .scrollTransition(.interactive, axis: .horizontal) { content, phase in
                                let resting = reduceMotion || phase.isIdentity
                                return content
                                    .scaleEffect(resting ? 1 : 0.96)
                                    .opacity(resting ? 1 : 0.86)
                            }
                    }
                }
                .scrollTargetLayout()
            }
            .id(deckGeneration)
            .scrollIndicators(.hidden)
            .decideCardPagingBehavior()
            .scrollPosition(id: $selectedPageID)
            .contentMargins(.horizontal, 20, for: .scrollContent)
        }
    }

    private func recipeCard(_ recipe: Recipe, snapshot: CookCandidateSnapshot, isCurrent: Bool) -> some View {
        let match = snapshot.matchesByID[recipe.id]
        return ViewThatFits(in: .vertical) {
            cardBody(recipe, match: match)
                .padding(20)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            ScrollView(.vertical) {
                cardBody(recipe, match: match)
                    .padding(20)
                    .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(.quaternary, lineWidth: 1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isStaticText)
        .accessibilityLabel(isCurrent ? accessibilitySummary(recipe, match: match) : "")
        .accessibilityValue(isCurrent ? "第 \(pageNumber) 道，共 \(pageTotal) 道" : "")
        .accessibilityActions {
            if isCurrent, hasPrevious { Button("上一道") { turnPage(.previous) } }
            if isCurrent, hasNext || canReroll { Button("下一道") { turnPage(.next) } }
        }
        .accessibilityIdentifier(isCurrent ? "cook.decide.recipe" : "cook.decide.recipe.inactive")
        .accessibilityHidden(!isCurrent)
    }

    private func cardBody(_ recipe: Recipe, match: RecipeMatch?) -> some View {
        VStack(spacing: 10) {
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
        .disabled(currentRecipe == nil)
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
        .disabled(!enabled)
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
        guard let currentRecipe else { return }
        let current = session.candidateSnapshot(in: store, ignoringKeyword: request.ignoringKeyword)
        guard let recipe = resolvedPool(current).first(where: { $0.id == currentRecipe.id }) else {
            syncPool(resolvedPool(current))
            return
        }
        onOpen(RecipeRoute(recipe: recipe, match: current.matchesByID[recipe.id], dietaryGoal: session.dietaryGoal))
    }

    private func syncPool(_ updated: [Recipe]) {
        if updated.isEmpty {
            activePool = []
            deck = []
            revealedCount = 0
            selectedPageID = nil
            deckGeneration += 1
            AccessibilityNotification.Announcement("这次还没找到合适的菜").post()
            return
        }
        if updated == activePool && !deck.isEmpty {
            return
        }

        activePool = updated
        let previousName = lastDecidedID.flatMap { id in updated.first { $0.id == id }?.name }
        guard let first = nextRandomRecipe(
            in: updated,
            excludingID: lastDecidedID,
            excludingNames: previousName.map { [$0] } ?? []
        ) else {
            deck = []
            revealedCount = 0
            selectedPageID = nil
            deckGeneration += 1
            return
        }

        let firstPage = DecidePage(recipe: first)
        var initialDeck = [firstPage]
        if updated.count > 1 {
            appendTrailingPages(to: &initialDeck, in: updated, targetCount: 1 + Self.trailingBufferCount)
        }
        deck = initialDeck
        revealedCount = 1
        selectedPageID = firstPage.id
        deckGeneration += 1
        lastDecidedID = first.id
        settledCount += 1
        AccessibilityNotification.Announcement("就做\(first.name)").post()
    }

    private func handlePageSelectionChange(from oldID: UUID?, to newID: UUID?) {
        guard let oldID,
              let newID,
              oldID != newID,
              deck.contains(where: { $0.id == oldID }),
              let newIndex = deck.firstIndex(where: { $0.id == newID }) else {
            return
        }

        if newIndex >= revealedCount {
            revealedCount = newIndex + 1
            if canReroll {
                ensureTrailingBuffer(in: activePool)
            }
        }
        let recipe = deck[newIndex].recipe
        lastDecidedID = recipe.id
        settledCount += 1
        let total = max(revealedCount, newIndex + 1)
        AccessibilityNotification.Announcement("\(recipe.name)，第 \(newIndex + 1) 道，共 \(total) 道").post()
    }

    private func requestReroll() {
        guard !showingFilters, canReroll, let currentIndex else { return }
        let targetIndex = revealedCount
        ensureTrailingBuffer(in: activePool, minimumCount: targetIndex + 1 + Self.trailingBufferCount)
        guard deck.indices.contains(targetIndex) else { return }

        if currentIndex < revealedCount - 1 {
            let current = deck[currentIndex].recipe
            let staged = deck[targetIndex].recipe
            if staged.id == current.id || staged.name == current.name {
                let lastRevealedName = deck[revealedCount - 1].recipe.name
                if let replacement = nextRandomRecipe(
                    in: activePool,
                    excludingID: current.id,
                    excludingNames: [current.name, lastRevealedName]
                ) {
                    deck[targetIndex] = DecidePage(id: deck[targetIndex].id, recipe: replacement)
                }
            }
        }

        scrollToPage(at: targetIndex)
    }

    private func turnPage(_ direction: DecidePageDirection) {
        guard !showingFilters, let currentIndex else { return }
        switch direction {
        case .previous:
            guard currentIndex > 0 else { return }
            scrollToPage(at: currentIndex - 1)
        case .next:
            if hasNext {
                scrollToPage(at: currentIndex + 1)
            } else {
                requestReroll()
            }
        }
    }

    private func scrollToPage(at index: Int) {
        guard deck.indices.contains(index) else { return }
        let targetID = deck[index].id
        if reduceMotion {
            selectedPageID = targetID
        } else {
            withAnimation(.smooth(duration: 0.32)) {
                selectedPageID = targetID
            }
        }
    }

    private func ensureTrailingBuffer(in pool: [Recipe], minimumCount: Int? = nil) {
        let targetCount = minimumCount ?? (revealedCount + Self.trailingBufferCount)
        var nextDeck = deck
        appendTrailingPages(to: &nextDeck, in: pool, targetCount: targetCount)
        if nextDeck.count != deck.count {
            deck = nextDeck
        }
    }

    private func appendTrailingPages(to pages: inout [DecidePage], in pool: [Recipe], targetCount: Int) {
        guard pool.count > 1 else { return }
        while pages.count < targetCount {
            let previous = pages.last?.recipe
            guard let next = nextRandomRecipe(
                in: pool,
                excludingID: previous?.id,
                excludingNames: previous.map { [$0.name] } ?? []
            ) else { break }
            pages.append(DecidePage(recipe: next))
        }
    }

    private func nextRandomRecipe(
        in pool: [Recipe],
        excludingID: Int?,
        excludingNames: Set<String>
    ) -> Recipe? {
        let alternatives = excludingNames.isEmpty ? pool : pool.filter { !excludingNames.contains($0.name) }
        return store.randomRecipe(from: alternatives.isEmpty ? pool : alternatives, excluding: excludingID)
    }
}

private enum DecidePageDirection {
    case previous, next
}

private extension View {
    @ViewBuilder
    func decideCardPagingBehavior() -> some View {
        if #available(iOS 18.0, *) {
            scrollTargetBehavior(.viewAligned(limitBehavior: .alwaysByOne))
        } else {
            scrollTargetBehavior(.viewAligned(limitBehavior: .always))
        }
    }
}
