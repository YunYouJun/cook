import CookCore
import SwiftUI

/// Search text and editable conditions stay local until the user confirms them.
struct SearchIntentSheet: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(CookAISettings.self) private var ai
    @Environment(\.dismiss) private var dismiss
    @State private var draft: SearchIntentDraft
    @State private var confirmsSending = false
    @State private var applyError: String?

    init(text: String) { _draft = State(initialValue: SearchIntentDraft(text: text)) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("如：鸡蛋和西葫芦，不要辣", text: $draft.text, axis: .vertical)
                        .lineLimit(2...5)
                        .privacySensitive()
                        .accessibilityIdentifier("intent.text")
                    Button("重新识别本地条件") { parseLocally() }
                        .disabled(draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    if ai.canUseOwnDeepSeek {
                        Button("智能找菜 · DeepSeek") { confirmsSending = true }
                            .disabled(draft.isLoading || draft.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("intent.smart")
                    }
                    if draft.isLoading {
                        HStack {
                            ProgressView().accessibilityLabel("正在识别")
                            Text("正在识别条件…")
                            Spacer()
                            Button("取消请求") { draft.cancel() }
                        }
                    }
                } header: { Text("这次想怎么做") } footer: {
                    Text("普通识别在本机完成。确认前不会修改当前选择；确认后合并条件，保留已有忌口和筛选。")
                }
                if draft.intent != nil { conditions }
                if let message = draft.message {
                    Section { Text(message).foregroundStyle(.secondary) }
                }
            }
            .accessibilityIdentifier("intent.form")
            .disabled(draft.isLoading)
            .overlay(alignment: .bottom) {
                if draft.isLoading {
                    Button("取消请求，保留本地条件") { draft.cancel() }
                        .buttonStyle(.borderedProminent).padding()
                }
            }
            .navigationTitle("确认找菜条件")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { draft.cancel(); dismiss() }
                        .accessibilityIdentifier("intent.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("应用条件") { apply() }
                        .disabled(!draft.canApply)
                        .accessibilityIdentifier("intent.apply")
                }
            }
            .confirmationDialog("发送本次文本到 DeepSeek？", isPresented: $confirmsSending, titleVisibility: .visible) {
                Button("发送并智能识别") { recognize() }
            } message: {
                Text("仅发送上方本次文本及公共词典、筛选规则，不发送账号、收藏、历史或已保存偏好。使用你自己的 Key，费用由你的 DeepSeek 账号承担。")
            }
            .alert("暂时无法应用", isPresented: Binding(get: { applyError != nil }, set: { if !$0 { applyError = nil } })) {
                Button("好", role: .cancel) { applyError = nil }
            } message: { Text(applyError ?? "") }
        }
        .task { parseLocally() }
        .onChange(of: draft.text) { draft.inputChanged(); applyError = nil }
        .onChange(of: ai.provider) { draft.cancel() }
        .onDisappear { draft.cancel() }
    }

    private var query: Binding<CookQuery> {
        Binding(get: { draft.intent?.query ?? CookQuery() }, set: { draft.intent?.query = $0 })
    }

    private var conditions: some View {
        Group {
            if let intent = draft.intent, !intent.isEmpty {
                ChipSection(title: "已识别", note: "轻点标签移除；在下方修改或补充") {
                    ForEach(intent.query.stuff, id: \.self) { name in
                        Chip(title: name, isSelected: true) { query.wrappedValue.stuff.removeAll { $0 == name } }
                            .accessibilityLabel("移除食材\(name)")
                    }
                    ForEach(intent.query.avoid, id: \.self) { name in
                        Chip(title: "忌口\(name)", isSelected: true) { query.wrappedValue.avoid.removeAll { $0 == name } }
                            .accessibilityLabel("移除忌口\(name)")
                    }
                    if intent.query.noSpicy {
                        Chip(title: "不吃辣", isSelected: true) { query.wrappedValue.noSpicy = false }
                            .accessibilityLabel("移除不吃辣")
                    }
                    if let tool = intent.query.tool {
                        Chip(title: store.toolLabel(tool), isSelected: true) { query.wrappedValue.tool = nil }
                            .accessibilityLabel("移除厨具\(store.toolLabel(tool))")
                    }
                    ForEach(intent.query.methodGroups, id: \.self) { method in
                        Chip(title: method, isSelected: true) { query.wrappedValue.methodGroups.removeAll { $0 == method } }
                            .accessibilityLabel("移除做法\(method)")
                    }
                    ForEach(intent.query.difficulty, id: \.self) { level in
                        Chip(title: level, isSelected: true) { query.wrappedValue.difficulty.removeAll { $0 == level } }
                            .accessibilityLabel("移除难度\(level)")
                    }
                    if let goal = intent.goal {
                        Chip(title: goal.title, isSelected: true) { draft.intent?.goal = nil }
                            .accessibilityLabel("移除目标\(goal.title)")
                    }
                }
            }
            Section {
                NavigationLink {
                    IntentListPicker(title: "食材", options: store.data.normalizer.unique(store.data.food.vegetable.map(\.name) + store.data.food.meat.map(\.name) + store.data.food.staple.map(\.name)), selected: query.stuff)
                } label: { LabeledContent("食材", value: summary(query.wrappedValue.stuff)) }
                NavigationLink {
                    IntentListPicker(title: "忌口", options: store.data.normalizer.unique(store.data.recipes.flatMap(\.stuff) + store.data.pantry.options), selected: query.avoid)
                } label: { LabeledContent("忌口", value: summary(query.wrappedValue.avoid)) }
                Toggle("不吃辣", isOn: query.noSpicy)
                    .accessibilityIdentifier("intent.noSpicy")
                Picker("厨具", selection: Binding(get: { query.wrappedValue.tool ?? "" }, set: { query.wrappedValue.tool = $0.isEmpty ? nil : $0 })) {
                    Text("不新增限制").tag("")
                    ForEach(store.data.food.tools, id: \.name) { Text(store.toolLabel($0.name)).tag($0.name) }
                }
                NavigationLink {
                    IntentListPicker(title: "做法", options: Recommender.methodGroups.map(\.label), selected: query.methodGroups)
                } label: { LabeledContent("做法", value: summary(query.wrappedValue.methodGroups)) }
                NavigationLink {
                    IntentListPicker(title: "难度", options: Recommender.difficultyLevels, selected: query.difficulty)
                } label: { LabeledContent("难度", value: summary(query.wrappedValue.difficulty)) }
                Picker("饮食目标", selection: Binding(get: { draft.intent?.goal?.rawValue ?? "" }, set: { draft.intent?.goal = DietaryGoal(rawValue: $0) })) {
                    Text("不新增目标").tag("")
                    ForEach(store.dietaryGoals) { Text($0.title).tag($0.rawValue) }
                }
            } header: { Text("可编辑条件") } footer: {
                Text(draft.intent?.source == .deepseek ? "来自 DeepSeek，经本机规则校验；菜谱仍从内置库中检索。" : "来自本地词表；点击任一项修改或移除条件。")
            }
            if let intent = draft.intent, !intent.unresolved.isEmpty {
                Section {
                    ForEach(Array(intent.unresolved.enumerated()), id: \.offset) { _, text in
                        Label(text, systemImage: "questionmark.circle").foregroundStyle(.secondary)
                    }
                    Toggle("仅应用上方条件，待确认项本次不生效", isOn: $draft.acceptsUnresolved)
                        .accessibilityIdentifier("intent.acceptPartial")
                } header: { Text("待确认") } footer: {
                    Text("可以修改原句后重新识别。未开放的目标和无法核实的条件不会自动生效。")
                }
            }
        }
    }

    private func summary(_ names: [String]) -> String { names.isEmpty ? "无新增条件" : names.joined(separator: "、") }

    private func parseLocally() {
        draft.parseLocally(data: store.data, goals: store.dietaryGoals)
        applyError = nil
    }

    private func recognize() {
        let data = store.data, goals = store.dietaryGoals
        draft.parseRemotely { text in
            let key = try ai.apiKeyForRequest()
            return try await DeepSeekIntentService().parse(text: text, key: key, data: data, availableGoals: goals)
        }
    }

    private func apply() {
        guard draft.canApply, let intent = draft.intent else { return }
        do { try session.applySearchIntent(intent, in: store); dismiss() }
        catch { applyError = (error as? SearchIntentApplicationError)?.localizedDescription ?? "条件已失效，请重新识别并确认。原有选择保持不变。" }
    }
}

private struct IntentListPicker: View {
    let title: String
    let options: [String]
    @Binding var selected: [String]
    @State private var search = ""

    var body: some View {
        List {
            ForEach(options.filter { search.isEmpty || $0.contains(search) }, id: \.self) { name in
                Button { selected = CookSession.toggled(selected, name) } label: {
                    HStack {
                        Text(name).foregroundStyle(.primary)
                        Spacer()
                        if selected.contains(name) { Image(systemName: "checkmark") }
                    }
                }
                .accessibilityAddTraits(selected.contains(name) ? .isSelected : [])
            }
        }
        .navigationTitle(title)
        .searchable(text: $search, prompt: "搜索\(title)")
    }
}
