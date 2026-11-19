import CookCore
import SwiftData
import SwiftUI

struct LearnedDishesView: View {
    @Environment(CookedRecipeRecords.self) private var records
    @Environment(RecipeStore.self) private var store
    @Environment(ShowcaseAssetLibrary.self) private var showcases
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Query private var privateRecords: [PrivateRecipeRecord]
    @ScaledMetric(relativeTo: .body) private var tileWidth: CGFloat = 148

    @State private var presentation = Presentation.badges
    @State private var selection: ShowcaseSelection?

    private enum Presentation {
        case badges
        case showcase
    }

    private struct ShowcaseSelection: Identifiable {
        let dish: Entry
        let source: DishShowcaseView.Source

        var id: UUID { dish.id }
    }

    private struct Entry: Identifiable {
        let record: LearnedRecord
        let name: String
        var catalogRecipeID: Int? = nil
        var unavailable: String? = nil
        var showcase: DishShowcaseView.Source? = nil

        var id: UUID { record.id }
    }

    private static let genericPlate = Bundle.main.url(forResource: "GenericShowcasePlate", withExtension: "usdz")

    var body: some View {
        Group {
            if let issue = records.storageIssue {
                ContentUnavailableView("暂时无法读取拿手菜", systemImage: "exclamationmark.circle", description: Text(issue))
            } else {
                let dishes = learnedDishes
                VStack(spacing: 0) {
                    Picker("拿手菜视图", selection: $presentation) {
                        Text("徽章册").tag(Presentation.badges)
                        Text("展柜").tag(Presentation.showcase)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("learnedDishes.presentation")
                    .frame(maxWidth: 480)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 8)

                    switch presentation {
                    case .badges:
                        if dishes.isEmpty {
                            ContentUnavailableView("还没有拿手菜", systemImage: "medal",
                                                   description: Text("在菜谱详情或厨房工作台确认学会后，徽章会出现在这里。"))
                                .accessibilityIdentifier("learnedDishes.empty")
                        } else {
                            badges(dishes)
                        }
                    case .showcase:
                        LearnedDishShowcaseGallery(dishes: dishes.filter { $0.showcase != nil })
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("我的拿手菜")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $selection) { selection in
            ShowcaseSheet(name: selection.dish.name, learnedAt: selection.dish.record.learnedAt, source: selection.source)
        }
        .onChange(of: records.progress.learned) { _, learned in
            if let selection, !learned.contains(where: { $0.id == selection.id }) {
                self.selection = nil
            }
        }
    }

    private var learnedDishes: [Entry] {
        let privateRecipes = (try? PrivateRecipeSnapshot(privateRecords))?.recipes ?? []
        let privateNames = Dictionary(uniqueKeysWithValues: privateRecipes.map { ($0.customId, $0.name) })
        return records.progress.entries(learnedOnly: true).compactMap { entry in
            entry.learned.map { dish(for: $0, privateNames: privateNames) }
        }
    }

    private func badges(_ dishes: [Entry]) -> some View {
        let columns = [GridItem(dynamicTypeSize.isAccessibilitySize ? .flexible() : .adaptive(minimum: tileWidth), spacing: 12)]
        return ScrollView {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 12) {
                ForEach(dishes) { dish in
                    if let showcase = dish.showcase {
                        Button {
                            selection = ShowcaseSelection(dish: dish, source: showcase)
                        } label: {
                            tile(for: dish)
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("查看菜肴成就")
                        .accessibilityIdentifier("learnedDishes.badge")
                    } else {
                        tile(for: dish)
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

    private func tile(for dish: Entry) -> some View {
        let learnedAt = dish.record.learnedAt
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
                if dish.showcase != nil {
                    Label("查看成就", systemImage: "sparkles")
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

    private func dish(for record: LearnedRecord, privateNames: [String: String]) -> Entry {
        switch record.recipeID {
        case let .catalog(id):
            if let recipe = store.recipesById[id] {
                return Entry(record: record, name: recipe.name, catalogRecipeID: id,
                             showcase: showcases.stores[id].map(DishShowcaseView.Source.downloads))
            }
            return Entry(record: record, name: record.recipeName ?? "这道菜", unavailable: "原菜谱暂不可用")
        case let .personal(id):
            if let name = privateNames[id] {
                return Entry(record: record, name: name, showcase: Self.genericPlate.map(DishShowcaseView.Source.localPlate))
            }
            return Entry(record: record, name: record.recipeName ?? "私人菜谱", unavailable: "私人菜谱已删除或暂不可用")
        }
    }

    private struct LearnedDishShowcaseGallery: View {
        let dishes: [Entry]
        @State private var selectedID: UUID?

        private var selected: Entry? {
            dishes.first { $0.id == selectedID } ?? dishes.first
        }

        var body: some View {
            if let selected, let source = selected.showcase {
                VStack(spacing: 0) {
                    if dishes.count > 1 {
                        Picker("展出菜品", selection: Binding(get: { selected.id }, set: { selectedID = $0 })) {
                            ForEach(dishes) { dish in
                                Text(dish.name).tag(dish.id)
                            }
                        }
                        .pickerStyle(.menu)
                        .accessibilityIdentifier("learnedDishes.featuredDish")
                    }
                    DishShowcaseView(name: selected.name, learnedAt: selected.record.learnedAt, source: source)
                        .id(selected.id)
                }
            } else {
                ContentUnavailableView("还没有可展出的菜", systemImage: "cube",
                                       description: Text("学会番茄炒蛋、蒸蛋羹、拍黄瓜或私人菜谱后，就能在这里展出。"))
                    .accessibilityIdentifier("learnedDishes.showcaseEmpty")
            }
        }
    }

    private struct ShowcaseSheet: View {
        let name: String
        let learnedAt: Date
        let source: DishShowcaseView.Source
        @Environment(\.dismiss) private var dismiss

        var body: some View {
            NavigationStack {
                DishShowcaseView(name: name, learnedAt: learnedAt, source: source)
                    .navigationTitle("菜肴成就")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("完成") { dismiss() }
                        }
                    }
            }
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
            .environment(ShowcaseAssetLibrary.live())
            .modelContainer(Persistence.makeContainer(inMemory: true))
            .preferredColorScheme(.dark)
    }
}

#Preview("我的拿手菜 · 空状态") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        NavigationStack { LearnedDishesView() }
            .environment(store)
            .environment(CookedRecipeRecords())
            .environment(ShowcaseAssetLibrary.live())
            .modelContainer(Persistence.makeContainer(inMemory: true))
    }
}
