import CookCore
import SwiftData
import SwiftUI

struct CookingFootprintLink: View {
    @Environment(CookedRecipeRecords.self) private var records

    var body: some View {
        NavigationLink { CookingFootprintView() } label: {
            HStack {
                SettingsRowLabel("做菜足迹", symbol: "leaf.fill", color: .orange)
                Spacer(minLength: 8)
                Text(summary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
        }
        .accessibilityIdentifier("settings.cookingFootprint")
    }

    private var summary: String {
        if records.storageIssue != nil { return "暂时无法读取" }
        let progress = records.progress
        if progress.cooked.isEmpty && progress.learned.isEmpty { return "开始记录" }
        return "学会 \(progress.learned.count) 道 · 做过 \(progress.cooked.count) 次"
    }
}

struct CookingFootprintView: View {
    @Environment(CookedRecipeRecords.self) private var records
    @Environment(RecipeStore.self) private var store
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
            Section {
                HStack(spacing: 20) {
                    statistic("做过", count: records.progress.cooked.count, unit: "次", symbol: "frying.pan")
                    Divider()
                    statistic("学会", count: records.progress.learned.count, unit: "道", symbol: "sparkles")
                }
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 12)
            }
            .accessibilityIdentifier("footprint.statistics")

            Section {
                Picker("记录范围", selection: $filter) {
                    ForEach(Filter.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
                .accessibilityIdentifier("footprint.filter")
            } footer: {
                if recipeID != nil { Text("正在查看这道菜的足迹，顶部统计为全部菜谱。") }
                Text("记录仅保存在这台设备。删除做过记录不会撤销学会标记。")
            }

            if let issue = records.storageIssue {
                Section { Text(issue).foregroundStyle(.secondary) }
            } else if days.isEmpty {
                Section {
                    ContentUnavailableView {
                        Label(filter == .learned ? "拿手菜，慢慢积累" : "留住每一次开火", systemImage: "leaf")
                    } description: {
                        Text(filter == .learned
                             ? "学会一道菜后，亲手为自己记下这个时刻吧。"
                             : "还没有留下下厨食迹，去挑一道喜欢的菜开火吧。")
                    }
                }
                .listRowBackground(Color.clear)
                .accessibilityIdentifier("footprint.empty")
            }

            ForEach(days) { day in
                Section(CookingDates.day(day.id)) {
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
                }
            }
        }
        .navigationTitle("做菜足迹")
        .accessibilityIdentifier("footprint.list")
        .alert("未能保存足迹", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(error ?? "") }
    }

    private func statistic(_ title: String, count: Int, unit: String, symbol: String) -> some View {
        VStack(spacing: 8) {
            Label(title, systemImage: symbol)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("\(count) \(unit)").font(.title2.weight(.semibold))
        }
        .frame(maxWidth: .infinity)
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
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                Text(name).foregroundStyle(.primary)
                Text(entry.date, format: .dateTime.hour().minute())
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if let unavailable { Text(unavailable).font(.caption).foregroundStyle(.secondary) }
            }
            Spacer(minLength: 0)
            if entry.learned != nil {
                Text("✨ 学会了")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.primary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(.orange.opacity(0.14), in: Capsule())
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("footprint.entry")
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
