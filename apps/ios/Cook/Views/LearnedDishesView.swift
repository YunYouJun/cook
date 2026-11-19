import CookCore
import SwiftData
import SwiftUI

struct LearnedDishesView: View {
    @Environment(CookedRecipeRecords.self) private var records
    @Environment(RecipeStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Query private var privateRecords: [PrivateRecipeRecord]
    @ScaledMetric(relativeTo: .body) private var tileWidth: CGFloat = 148

    @State private var selectedTomatoEgg: LearnedRecord?

    private struct Dish {
        let name: String
        var catalogRecipeID: Int? = nil
        var unavailable: String? = nil
    }

    var body: some View {
        Group {
            if let issue = records.storageIssue {
                ContentUnavailableView("暂时无法读取拿手菜", systemImage: "exclamationmark.circle", description: Text(issue))
            } else if records.progress.learned.isEmpty {
                ContentUnavailableView("还没有拿手菜", systemImage: "medal",
                                       description: Text("在菜谱详情或厨房工作台确认学会后，徽章会出现在这里。"))
                    .accessibilityIdentifier("learnedDishes.empty")
            } else {
                badges
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("我的拿手菜")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $selectedTomatoEgg) { record in
            TomatoEggAchievementView(learnedAt: record.learnedAt)
        }
        .onChange(of: records.progress.learned) { _, learned in
            if let selectedTomatoEgg, !learned.contains(where: { $0.id == selectedTomatoEgg.id }) {
                self.selectedTomatoEgg = nil
            }
        }
    }

    private var badges: some View {
        let learned = records.progress.entries(learnedOnly: true).compactMap(\.learned)
        let privateRecipes = (try? PrivateRecipeSnapshot(privateRecords))?.recipes ?? []
        let privateNames = Dictionary(uniqueKeysWithValues: privateRecipes.map { ($0.customId, $0.name) })
        let columns = [GridItem(dynamicTypeSize.isAccessibilitySize ? .flexible() : .adaptive(minimum: tileWidth), spacing: 12)]
        return ScrollView {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                ForEach(learned) { record in
                    let dish = dish(for: record, privateNames: privateNames)
                    if dish.catalogRecipeID == TomatoEggAchievementView.recipeID {
                        Button { selectedTomatoEgg = record } label: {
                            tile(for: dish, learnedAt: record.learnedAt)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("打开可旋转的番茄炒蛋模型")
                        .accessibilityIdentifier("learnedDishes.badge")
                    } else {
                        tile(for: dish, learnedAt: record.learnedAt)
                            .accessibilityIdentifier("learnedDishes.badge")
                    }
                }
            }
            .frame(maxWidth: 720)
            .padding(20)
            .frame(maxWidth: .infinity)
        }
        .accessibilityIdentifier("learnedDishes.collection")
    }

    private func tile(for dish: Dish, learnedAt: Date) -> some View {
        let usesLargeText = dynamicTypeSize.isAccessibilitySize
        let layout = usesLargeText
            ? AnyLayout(HStackLayout(alignment: .top, spacing: 12))
            : AnyLayout(VStackLayout(spacing: 10))
        return layout {
            LearnedDishBadge(catalogRecipeID: dish.catalogRecipeID, size: usesLargeText ? 52 : 84)
            VStack(alignment: usesLargeText ? .leading : .center, spacing: 4) {
                Text(dish.name).font(.headline)
                Text("首次学会 \(CookingDates.plaque(learnedAt))")
                    .font(.footnote.monospacedDigit())
                    .foregroundStyle(CookingJournalPalette.warmInk)
                    .accessibilityLabel("首次学会 \(CookingDates.celebration(learnedAt))")
                if dish.catalogRecipeID == TomatoEggAchievementView.recipeID {
                    Label("查看立体成就", systemImage: "rotate.3d")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(CookingJournalPalette.warmInk)
                }
                if let unavailable = dish.unavailable {
                    Text(unavailable).font(.caption).foregroundStyle(.secondary)
                }
            }
            .multilineTextAlignment(usesLargeText ? .leading : .center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: usesLargeText ? .leading : .center)
        }
        .frame(maxWidth: .infinity)
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
        .accessibilityElement(children: .combine)
    }

    private func dish(for record: LearnedRecord, privateNames: [String: String]) -> Dish {
        switch record.recipeID {
        case let .catalog(id):
            if let recipe = store.recipesById[id] { return Dish(name: recipe.name, catalogRecipeID: recipe.id) }
            return Dish(name: record.recipeName ?? "这道菜", unavailable: "原菜谱暂不可用")
        case let .personal(id):
            if let name = privateNames[id] { return Dish(name: name) }
            return Dish(name: record.recipeName ?? "私人菜谱", unavailable: "私人菜谱已删除或暂不可用")
        }
    }
}

#Preview("我的拿手菜 · 徽章") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        let records = CookedRecipeRecords()
        let _ = try? records.confirmLearned(.catalog(250), name: "番茄炒蛋")
        let _ = try? records.confirmLearned(.catalog(588), name: "蒸蛋羹")
        let _ = try? records.confirmLearned(.catalog(497), name: "拍黄瓜")
        let _ = try? records.confirmLearned(.personal("preview"), name: "奶奶的葱油拌面")
        NavigationStack { LearnedDishesView() }
            .environment(store)
            .environment(records)
            .modelContainer(Persistence.makeContainer(inMemory: true))
            .preferredColorScheme(.dark)
    }
}

#Preview("我的拿手菜 · 空状态") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        NavigationStack { LearnedDishesView() }
            .environment(store)
            .environment(CookedRecipeRecords())
            .modelContainer(Persistence.makeContainer(inMemory: true))
    }
}
