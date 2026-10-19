import CookCore
import SwiftUI

/// 做菜：选食材和筛选条件，底部按钮进入推荐结果
struct CookView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session

    var body: some View {
        let matchCount = store.recommender.recommend(session.options).count
        let warnings = findIncompatibleRules(session.selectedStuff, rules: store.data.incompatibleRules)

        NavigationStack {
            List {
                if !warnings.isEmpty {
                    IncompatibleWarning(rules: warnings)
                }
                stuffSection("蔬菜", store.data.food.vegetable)
                stuffSection("肉和蛋", store.data.food.meat)
                stuffSection("主食（可选）", store.data.food.staple)

                ChipSection(title: "厨具（可选）", note: session.tool == nil ? nil : "只看用它做的菜") {
                    ForEach(store.data.food.tools, id: \.name) { item in
                        Chip(title: store.toolLabel(item.name), isSelected: session.tool == item.name) {
                            session.toggleTool(item.name)
                        }
                    }
                }
                ChipSection(title: "做法（可选）", note: session.methodGroups.isEmpty ? nil : "只看这些做法") {
                    ForEach(Recommender.methodGroups, id: \.label) { group in
                        Chip(title: group.label, isSelected: session.methodGroups.contains(group.label)) {
                            session.toggleMethodGroup(group.label)
                        }
                    }
                }
                ChipSection(title: "难度（可选）", note: session.difficulty.isEmpty ? nil : "没标难度的菜会被隐藏") {
                    ForEach(Recommender.difficultyLevels, id: \.self) { level in
                        Chip(title: level, isSelected: session.difficulty.contains(level)) {
                            session.toggleDifficulty(level)
                        }
                    }
                }
                Section("调料和忌口") {
                    PreferenceRows()
                }
            }
            .navigationTitle("做菜")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("清空") { session.clearStuff() }
                        .disabled(session.selectedStuff.isEmpty)
                }
            }
            .safeAreaInset(edge: .bottom) {
                NavigationLink(value: ResultsRoute()) {
                    Text(session.selectedStuff.isEmpty ? "先选几样食材" : "看看能做什么（\(matchCount)）")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(session.selectedStuff.isEmpty)
                .padding(.horizontal)
                .padding(.top, 16)
                .padding(.bottom, 8)
                .background {
                    // 按钮没选食材时是半透明的，垫一层底色，免得和下面滚过的列表叠在一起
                    let background = Color(uiColor: .systemGroupedBackground)
                    VStack(spacing: 0) {
                        LinearGradient(colors: [background.opacity(0), background], startPoint: .top, endPoint: .bottom)
                            .frame(height: 16)
                        background
                    }
                    .ignoresSafeArea(edges: .bottom)
                }
            }
            .navigationDestination(for: ResultsRoute.self) { _ in
                ResultsView()
            }
            .recipeDestinations()
        }
        .sensoryFeedback(.selection, trigger: session.selectedStuff)
    }

    @ViewBuilder
    private func stuffSection(_ title: String, _ items: [StuffItem]) -> some View {
        let count = items.filter { session.selectedStuff.contains($0.name) }.count
        ChipSection(title: title, note: count > 0 ? "已选 \(count) 种" : nil) {
            ForEach(items, id: \.name) { item in
                Chip(title: item.name, emoji: item.emoji, isSelected: session.selectedStuff.contains(item.name)) {
                    session.toggleStuff(item.name)
                }
            }
        }
    }
}

struct ResultsRoute: Hashable {}

/// 已选食材里有相克组合时的提醒
struct IncompatibleWarning: View {
    let rules: [IncompatibleRule]

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Label("食物相克提醒", systemImage: "exclamationmark.triangle.fill")
                    .font(.headline)
                    .foregroundStyle(.orange)
                ForEach(rules, id: \.self) { rule in
                    Text("【\(rule.foodA)】+【\(rule.foodB)】：\(rule.reason)")
                        .font(.subheadline)
                }
            }
            .padding(.vertical, 4)
        }
    }
}

/// 家里常备、忌口、不吃辣，做菜页和「我的」共用
struct PreferenceRows: View {
    @Environment(CookSession.self) private var session

    var body: some View {
        NavigationLink {
            PantryPicker()
        } label: {
            LabeledContent("家里常备", value: session.pantry.isEmpty ? "未选择" : session.pantry.joined(separator: "、"))
        }
        NavigationLink {
            AvoidPicker()
        } label: {
            LabeledContent("忌口", value: session.avoid.isEmpty ? "无" : session.avoid.joined(separator: "、"))
        }
        Toggle("不吃辣", isOn: Binding(get: { session.noSpicy }, set: { session.setNoSpicy($0) }))
    }
}

struct PantryPicker: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session

    var body: some View {
        List {
            Section {
                ForEach(store.data.pantry.options, id: \.self) { name in
                    CheckRow(title: name, isSelected: session.pantry.contains(name)) {
                        session.togglePantry(name)
                    }
                }
            } footer: {
                Text("家里常备的调料不算缺少的食材。")
            }
        }
        .navigationTitle("家里常备")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("恢复默认") { session.resetPantry(to: store.data.pantry.defaults) }
            }
        }
    }
}

struct AvoidPicker: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session

    var body: some View {
        List {
            avoidSection("蔬菜", store.data.food.vegetable)
            avoidSection("肉和蛋", store.data.food.meat)
            avoidSection("主食", store.data.food.staple)
        }
        .navigationTitle("忌口")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("清空") { session.clearAvoid() }
                    .disabled(session.avoid.isEmpty)
            }
        }
    }

    private func avoidSection(_ title: String, _ items: [StuffItem]) -> some View {
        Section(title) {
            ForEach(items, id: \.name) { item in
                CheckRow(title: "\(item.emoji) \(item.name)", isSelected: session.avoid.contains(item.name)) {
                    session.toggleAvoid(item.name)
                }
            }
        }
    }
}
