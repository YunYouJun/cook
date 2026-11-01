import CookCore
import SwiftData
import SwiftUI
import UIKit

/// Three suggestions with a decision sheet shared by the library dice entry.
struct CookInspirationSection: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(FavoriteActions.self) private var favoriteActions
    @Environment(AppRouter.self) private var router
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @Query(filter: #Predicate<Favorite> { $0.deletedAt == nil }) private var favorites: [Favorite]
    let decide: (RecipeRoute) -> Void
    var isDetailPresented = false
    /// 筛选、决定页或其他弹层已经打开时，不再从灵感区响应摇一摇。
    var suppressesShake = false
    @Binding var isVisible: Bool
    let presentRandom: ([Int]) -> Void
    @State private var batchIDs: [Int] = []
    @State private var revision = 0
    @AccessibilityFocusState private var resultsFocused: Bool

    private var context: [String] {
        CookDiscoverySummary.lines(session: session, store: store)
    }

    var body: some View {
        let candidates = session.candidateSnapshot(in: store)
        let pool = candidates.recipes
        let recipesByID = Dictionary(pool.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let batch = batchIDs.compactMap { recipesByID[$0] }
        let favoriteIDs = Set(favorites.map(\.recipeId))
        let canShuffle = Set(pool.map { $0.name.trimmingCharacters(in: .whitespacesAndNewlines) }).count > batch.count

        Section {
            VStack(alignment: .leading, spacing: 8) {
                inspirationTitle
                if !context.isEmpty {
                    Text(context.joined(separator: " · "))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("discovery.context")
                }
                if !batch.isEmpty {
                    CookInspirationActions(canShuffle: canShuffle, shuffle: { shuffle(from: pool) }, decide: { presentRandom(batch.map(\.id)) })
                }
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("discovery.cardHeader")
            if batch.isEmpty {
                CookInspirationEmptyState(explanation: emptyExplanation, hasScene: session.discoveryScene != nil, reset: resetScene)
            } else {
                ForEach(batch) { recipe in
                    Button {
                        decide(route(for: recipe, candidates: candidates))
                    } label: {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 8) {
                                RecipeRow(recipe: recipe, match: candidates.matchesByID[recipe.id], dietaryGoal: session.dietaryGoal)
                                if let scene = session.discoveryScene {
                                    Text(CookDiscovery.reason(for: recipe, filters: scene.filters))
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "chevron.right")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.tertiary)
                                .accessibilityHidden(true)
                        }
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                        .accessibilityElement(children: .combine)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("discovery.recipe.\(recipe.id)")
                    .accessibilityHint("查看做法")
                    .recipeActions(recipe, isFavorite: favoriteIDs.contains(recipe.id), store: store) {
                        favoriteActions.set(recipe.id, isFavorite: !favoriteIDs.contains(recipe.id))
                    }
                }
            }
        } footer: {
            if !batch.isEmpty {
                Text(canShuffle ? "先看这几道，有想吃的就点开做法。" : "当前条件下有 \(batch.count) 道不同的菜，都在这里了。")
            }
        }
        .onChange(of: pool, initial: true) { _, updated in
            batchIDs = CookDiscovery.sample(updated).map(\.id)
        }
        .onAppear { isVisible = true }
        .onDisappear { isVisible = false }
        .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
            guard isVisible, scenePhase == .active, router.tab == .cook, !isDetailPresented, !suppressesShake, !batch.isEmpty else { return }
            presentRandom(batch.map(\.id))
        }
        .sensoryFeedback(.selection, trigger: revision)
    }

    private var inspirationTitle: some View {
        Text("给你一点灵感")
            .font(.headline)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
            .accessibilityFocused($resultsFocused)
    }

    private var emptyExplanation: String {
        CookDiscoverySummary.emptyExplanation(session: session, store: store)
    }

    private func route(for recipe: Recipe, candidates: CookCandidateSnapshot) -> RecipeRoute {
        RecipeRoute(recipe: recipe, match: candidates.matchesByID[recipe.id], dietaryGoal: session.dietaryGoal)
    }

    private func resetScene() {
        session.discoveryScene = nil
        resultsFocused = true
    }

    private func shuffle(from pool: [Recipe]) {
        let previous = pool.filter { batchIDs.contains($0.id) }
        let next = CookDiscovery.sample(pool, avoiding: previous)
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) { batchIDs = next.map(\.id) }
        revision += 1
        UIAccessibility.post(notification: .announcement, argument: "已换一组灵感，显示 \(next.count) 道菜")
    }
}

private struct CookInspirationEmptyState: View {
    let explanation: String
    let hasScene: Bool
    let reset: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("这次还没找到合适的菜。")
                .font(.headline)
                .accessibilityIdentifier("discovery.empty")
            Text(explanation)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if hasScene {
                Button("取消这次场景", action: reset)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("discovery.reset")
            }
        }
        .padding(.vertical, 8)
    }
}

private struct CookInspirationActions: View {
    let canShuffle: Bool
    let shuffle: () -> Void
    let decide: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 10) { buttons(wrapping: false) }
            VStack(alignment: .leading, spacing: 0) { buttons(wrapping: true) }
        }
        .labelStyle(.titleAndIcon)
        .buttonStyle(.bordered)
        .font(.subheadline)
    }

    @ViewBuilder
    private func buttons(wrapping: Bool) -> some View {
        Button(action: shuffle) {
            Label("换三道", systemImage: "arrow.triangle.2.circlepath")
                .fixedSize(horizontal: !wrapping, vertical: true)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .disabled(!canShuffle)
        .accessibilityHint("在当前条件里再找三道菜")
        .accessibilityIdentifier("discovery.shuffle")
        Button(action: decide) {
            Label("帮我决定", systemImage: "dice")
                .fixedSize(horizontal: !wrapping, vertical: true)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .accessibilityHint("从这几道里选一道，先展示再打开做法，也可以摇一摇手机")
        .accessibilityIdentifier("discovery.decide")
    }
}
