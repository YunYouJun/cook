import CookCore
import SwiftData
import SwiftUI

struct CookingFootprintLink: View {
    @Environment(CookedRecipeRecords.self) private var records

    var body: some View {
        NavigationLink { CookingFootprintView() } label: {
            SettingsRowLabel("做菜足迹", symbol: "book.closed.fill", color: CookPalette.primaryAction, subtitle: summary)
        }
        .accessibilityIdentifier("settings.cookingFootprint")
    }

    private var summary: String {
        if records.storageIssue != nil { return "暂时无法读取" }
        let progress = records.progress
        if progress.cooked.isEmpty && progress.learned.isEmpty { return "回看做过的菜和学会的日子" }
        return "学会 \(progress.learned.count) 道 · 做过 \(progress.cooked.count) 次"
    }
}

struct CookingFootprintView: View {
    @Environment(CookedRecipeRecords.self) private var records
    @Environment(RecipeStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Query private var privateRecords: [PrivateRecipeRecord]
    @State private var filter: Filter = .all
    @State private var error: String?
    var recipeID: CookingRecipeID? = nil

    private enum Filter: String, CaseIterable {
        case all = "全部记录"
        case learned = "已学会"
    }

    private struct Day: Identifiable {
        var id: Date
        var entries: [CookingFootprintEntry]
    }

    private var days: [Day] {
        let entries = records.progress.entries(learnedOnly: filter == .learned, for: recipeID)
        return Dictionary(grouping: entries) { Calendar.current.startOfDay(for: $0.date) }
            .map { Day(id: $0.key, entries: $0.value) }
            .sorted { $0.id > $1.id }
    }

    private var privateRecipes: [String: PrivateRecipe] {
        let recipes = (try? PrivateRecipeSnapshot(privateRecords))?.recipes ?? []
        return Dictionary(recipes.map { ($0.customId, $0) }, uniquingKeysWith: { first, _ in first })
    }

    var body: some View {
        let days = days
        let privateRecipes = privateRecipes
        List {
            if let issue = records.storageIssue {
                Section { Text(issue).foregroundStyle(.secondary) }
            } else {
                Section {
                    VStack(spacing: 16) {
                        overview(privateRecipes: privateRecipes)
                        Picker("记录范围", selection: $filter) {
                            ForEach(Filter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        .accessibilityIdentifier("footprint.filter")
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
            }

            if records.storageIssue == nil && days.isEmpty {
                Section {
                    ContentUnavailableView {
                        Label(filter == .learned ? "还没有学会记录" : "还没有下厨记录",
                              systemImage: filter == .learned ? "sparkles" : "book.closed")
                    } description: {
                        Text(filter == .learned
                             ? "在菜谱详情或厨房工作台确认学会后，这里会记下日期。"
                             : "在菜谱详情点「我做过这道」，记下第一次下厨。")
                    }
                }
                .listRowBackground(Color.clear)
                .accessibilityIdentifier("footprint.empty")
            }

            ForEach(days) { day in
                Section {
                    ForEach(day.entries) { entry in
                        entryRow(entry, privateRecipes: privateRecipes)
                            .swipeActions(edge: .trailing) {
                                if let cookedID = entry.cookedRecordID {
                                    Button("删除做过记录", role: .destructive) {
                                        perform { try records.removeCooked(cookedID) }
                                    }
                                } else if entry.learned != nil {
                                    Button("撤销学会", role: .destructive) {
                                        perform { try records.removeLearned(entry.recipeID) }
                                    }
                                }
                            }
                            .contextMenu {
                                if let cookedID = entry.cookedRecordID {
                                    Button("删除这次做过记录", role: .destructive) {
                                        perform { try records.removeCooked(cookedID) }
                                    }
                                }
                                if entry.learned != nil {
                                    Button("撤销「学会」标记", role: .destructive) {
                                        perform { try records.removeLearned(entry.recipeID) }
                                    }
                                }
                            }
                    }
                } header: {
                    Text(CookingDates.day(day.id))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                }
            }
        }
        .navigationTitle("做菜足迹")
        .accessibilityIdentifier("footprint.list")
        .alert("未能保存足迹", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(error ?? "") }
    }

    private func overview(privateRecipes: [String: PrivateRecipe]) -> some View {
        let cooked = recipeID.map { records.cookedRecords(for: $0).count } ?? records.progress.cooked.count
        let learned = recipeID.map { records.learnedRecord(for: $0) == nil ? 0 : 1 } ?? records.progress.learned.count
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 16))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 16))
        let headerLayout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 8))
        return VStack(alignment: .leading, spacing: 16) {
            headerLayout {
                overviewTitle(privateRecipes: privateRecipes)
                    .frame(maxWidth: .infinity, alignment: .leading)
                deviceLabel
            }
            Divider()
            layout {
                statistic("做过", count: cooked, unit: "次", symbol: "frying.pan", tint: CookPalette.primaryAction)
                statistic("学会", count: learned, unit: "道", symbol: "sparkles", tint: CookingJournalPalette.warmInk)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 22)
                .fill(CookPalette.inputBackground)
                .overlay {
                    RoundedRectangle(cornerRadius: 22)
                        .fill(.linearGradient(colors: [CookPalette.primaryAction.opacity(0.13), .clear],
                                              startPoint: .topLeading, endPoint: .bottomTrailing))
                }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("footprint.statistics")
    }

    private func overviewTitle(privateRecipes: [String: PrivateRecipe]) -> some View {
        let title = switch recipeID {
        case let .catalog(id): store.recipesById[id]?.name ?? "这道菜的足迹"
        case let .personal(id): privateRecipes[id]?.name ?? "这道菜的足迹"
        case nil: "累计足迹"
        }
        return Text(title)
            .font(.headline)
            .accessibilityAddTraits(.isHeader)
    }

    private var deviceLabel: some View {
        HStack(spacing: 4) {
            Image(systemName: "internaldrive")
                .accessibilityHidden(true)
            Text("仅此设备")
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .fixedSize()
    }

    private func statistic(_ title: String, count: Int, unit: String, symbol: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: symbol)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(tint)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(count, format: .number)
                    .font(.system(.largeTitle, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                    .minimumScaleFactor(0.65)
                    .lineLimit(1)
                Text(unit).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title) \(count) \(unit)")
    }

    @ViewBuilder
    private func entryRow(_ entry: CookingFootprintEntry, privateRecipes: [String: PrivateRecipe]) -> some View {
        switch entry.recipeID {
        case let .catalog(id):
            if let recipe = store.recipesById[id] {
                NavigationLink { RecipeDetailView(route: RecipeRoute(recipe: recipe)) } label: {
                    entryLabel(entry, name: recipe.name)
                }
            } else {
                entryLabel(entry, name: entry.recipeName ?? "菜谱暂不可用", unavailable: "原菜谱暂不可用")
            }
        case let .personal(id):
            if let recipe = privateRecipes[id] {
                NavigationLink { PrivateRecipeDetailView(customId: id) } label: {
                    entryLabel(entry, name: recipe.name)
                }
            } else {
                entryLabel(entry, name: entry.recipeName ?? "已删除的私人菜谱", unavailable: "私人菜谱已删除或暂不可用")
            }
        }
    }

    private func entryLabel(_ entry: CookingFootprintEntry, name: String, unavailable: String? = nil) -> some View {
        HStack(alignment: .center, spacing: 12) {
            if !dynamicTypeSize.isAccessibilitySize {
                Image(systemName: unavailable != nil ? "book.closed" : entry.learned != nil ? "sparkles" : "frying.pan")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(entry.learned != nil ? CookingJournalPalette.warmInk : CookPalette.primaryAction)
                    .frame(width: 36, height: 36)
                    .background(entry.learned != nil ? CookingJournalPalette.warmFill : CookPalette.primaryAction.opacity(0.10),
                                in: RoundedRectangle(cornerRadius: 11))
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(name).font(.body.weight(.medium)).foregroundStyle(.primary)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 8) { entryMetadata(entry) }
                    VStack(alignment: .leading, spacing: 4) { entryMetadata(entry) }
                }
                if let unavailable {
                    Text(unavailable).font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("footprint.entry")
    }

    @ViewBuilder
    private func entryMetadata(_ entry: CookingFootprintEntry) -> some View {
        Text(entry.date, format: .dateTime.hour().minute())
            .font(.caption)
            .foregroundStyle(.secondary)
            .monospacedDigit()
        if entry.learned != nil {
            Text("✨ 学会了")
                .font(.caption.weight(.medium))
                .foregroundStyle(CookingJournalPalette.warmInk)
        }
    }

    private func perform(_ action: () throws -> Void) {
        do { try action() }
        catch { self.error = error.localizedDescription }
    }
}

#Preview("做菜足迹 · 空状态") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        NavigationStack { CookingFootprintView() }
            .environment(store)
            .environment(CookedRecipeRecords())
            .modelContainer(Persistence.makeContainer(inMemory: true))
    }
}
