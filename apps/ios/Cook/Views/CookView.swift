import CookCore
import SwiftUI

/// 做菜：选食材和厨具，或者在搜索框里说一句话；做法、难度和忌口放在「筛选」里。
/// 窄屏（iPhone、折叠屏外屏、分屏后变窄）点底部按钮进入推荐结果；
/// 宽屏（iPad、iPhone Duo 展开、大屏 iPhone 横屏）左边选食材，右边的推荐结果跟着实时更新
struct CookView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(CookSession.self) private var session
    @Environment(FeedbackClient.self) private var feedback
    @State private var showingFilters = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var basket = BasketAnimator()
    @State private var input = ""
    @State private var isInputPresented = false
    @State private var notice: InputNotice?
    /// 宽屏时一打开就并排显示两栏，不等用户点开侧栏
    @State private var columns: NavigationSplitViewVisibility = .all

    var body: some View {
        let toolOnly = session.selectedStuff.isEmpty && session.tool != nil
        let matchCount = toolOnly
            ? store.recommender.recipes(using: session.tool ?? "", options: session.options).count
            : store.recommender.recommend(session.options).count
        let emptySignal = emptySignalID(matchCount: matchCount)
        let warnings = findIncompatibleRules(session.selectedStuff, rules: store.data.incompatibleRules)
        let extras = session.selectedStuff.filter { !store.isCatalogStuff($0) }

        Group {
            if sizeClass == .regular {
                NavigationSplitView(columnVisibility: $columns) {
                    chooser(warnings: warnings, extras: extras, emptySignal: emptySignal)
                        .navigationSplitViewColumnWidth(min: 320, ideal: 400, max: 480)
                } detail: {
                    NavigationStack {
                        ResultsView()
                            .recipeDestinations()
                    }
                }
                // 竖着拿的 iPad 和展开的折叠屏也并排显示两栏，不把选食材的一栏收起来
                .navigationSplitViewStyle(.balanced)
            } else {
                NavigationStack {
                    chooser(warnings: warnings, extras: extras, emptySignal: emptySignal)
                        .bottomActionBar {
                            resultsButton(matchCount: matchCount)
                        }
                        .navigationDestination(for: ResultsRoute.self) { _ in
                            ResultsView()
                        }
                        .recipeDestinations()
                }
            }
        }
        .overlay {
            BasketFlightLayer(animator: basket)
        }
        .sensoryFeedback(.selection, trigger: session.selectedStuff)
        .sensoryFeedback(trigger: notice) { _, notice in
            switch notice?.kind {
            case .applied?: return .success
            case .notRecognized?: return .warning
            default: return nil
            }
        }
    }

    /// 选食材的列表：两种布局共用，宽屏放在左栏，窄屏是导航的第一页
    private func chooser(warnings: [IncompatibleRule], extras: [String], emptySignal: String?) -> some View {
        List {
            if let notice {
                InputNoticeRow(notice: notice) {
                    withAnimation(.snappy) { self.notice = nil }
                }
            }
            if !warnings.isEmpty {
                IncompatibleWarning(rules: warnings)
            }
            // 输入的、词典里没有的食材（比如「秋葵」），点一下移除
            if !extras.isEmpty {
                ChipSection(title: "输入的食材", note: "词典里没有，按名字找菜") {
                    ForEach(extras, id: \.self) { name in
                        Chip(title: name, isSelected: true) {
                            withAnimation(.snappy) { session.toggleStuff(name) }
                        }
                    }
                }
            }
            stuffSection("蔬菜", store.data.food.vegetable)
            stuffSection("肉和蛋", store.data.food.meat)
            stuffSection("主食（可选）", store.data.food.staple)

            ChipSection(title: "厨具（可选）", note: toolNote) {
                ForEach(store.data.food.tools, id: \.name) { item in
                    Chip(title: store.toolLabel(item.name), isSelected: session.tool == item.name) {
                        session.toggleTool(item.name)
                    }
                }
            }
        }
        .navigationTitle("做菜")
        .searchable(text: $input, isPresented: $isInputPresented, placement: .navigationBarDrawer(displayMode: .always), prompt: "说说有什么，如：土豆和鸡蛋，不要辣")
        .searchSuggestions {
            ForEach(store.stuffSuggestions(for: input, excluding: session.selectedStuff), id: \.name) { item in
                Button {
                    addSuggestion(item.name)
                } label: {
                    Label {
                        Text(item.name)
                            .foregroundStyle(Color.primary)
                    } icon: {
                        Text(item.emoji)
                    }
                }
                .accessibilityLabel("添加\(item.name)")
            }
        }
        .onSubmit(of: .search) {
            applySentence()
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                let count = session.activeFilterCount
                Button {
                    showingFilters = true
                } label: {
                    Label("筛选", systemImage: count > 0 ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
                        .contentTransition(.symbolEffect(.replace))
                }
                .accessibilityValue(count > 0 ? "已选 \(count) 项" : "")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button("清空") {
                    withAnimation(.snappy) { session.clearStuff() }
                }
                .disabled(!session.hasSelection)
            }
        }
        // 选了食材却一道菜都没有，停留 3 秒后匿名记一次，用来发现缺哪些菜；条件一变就重新计时
        .task(id: emptySignal) {
            guard emptySignal != nil else {
                return
            }
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else {
                return
            }
            feedback.signalEmpty(stuff: session.selectedStuff, tool: session.tool, mode: session.mode)
        }
        // 识别提示看过就收起；新的提示会重新计时
        .task(id: notice) {
            guard notice != nil else {
                return
            }
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else {
                return
            }
            withAnimation(.snappy) { notice = nil }
        }
        .sheet(isPresented: $showingFilters) {
            FilterSheet()
                .presentationDetents([.medium, .large])
                // 半屏时系统默认是半透明玻璃，后面选中的蓝色食材会透上来，看着像选中了某个做法
                .presentationBackground(Color(uiColor: .systemGroupedBackground))
        }
    }

    /// 底部「看看能做什么」按钮，只在窄屏显示
    private func resultsButton(matchCount: Int) -> some View {
        NavigationLink(value: ResultsRoute()) {
            HStack(spacing: 10) {
                BasketStack(items: basketItems)
                // 只让数字滚动，文字不跟着变形
                HStack(spacing: 0) {
                    if session.selectedStuff.isEmpty, let tool = session.tool {
                        Text("看看用\(store.toolLabel(tool))能做什么（")
                    } else if session.selectedStuff.isEmpty {
                        Text("先选几样食材")
                    } else {
                        Text("看看能做什么（")
                    }
                    if session.hasSelection {
                        Text("\(matchCount)")
                            .contentTransition(.numericText(value: Double(matchCount)))
                            .animation(.snappy, value: matchCount)
                        Text("）")
                    }
                }
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .accessibilityElement(children: .combine)
            }
            .font(.headline)
            .frame(maxWidth: .infinity)
        }
        .prominentButtonStyle()
        .controlSize(.large)
        .disabled(!session.hasSelection)
        .modifier(BasketBounce(animator: basket))
        .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { basket.target = $0 }
    }

    private var toolNote: String? {
        guard session.tool != nil else {
            return nil
        }
        return session.selectedStuff.isEmpty ? "不选食材，也能看用它做的菜" : "只看用它做的菜"
    }

    /// 底部按钮里的「菜篮子」：最近选的三样食材
    private var basketItems: [BasketStack.Item] {
        session.selectedStuff.suffix(3).map { name in
            BasketStack.Item(name: name, emoji: store.emoji(forStuff: name) ?? String(name.prefix(1)))
        }
    }

    /// 选中（不是取消）食材时，从标签飞一个 emoji 到底部按钮；减弱动态效果或宽屏时不飞
    private func launchFlight(_ item: StuffItem, from frame: CGRect) {
        // 宽屏没有底部按钮，结果直接出现在右栏
        guard sizeClass != .regular, !reduceMotion, !session.selectedStuff.contains(item.name) else {
            return
        }
        basket.launch(label: item.emoji.isEmpty ? item.name : item.emoji, isEmoji: !item.emoji.isEmpty, from: frame)
    }

    /// 和 Web 一样，按菜名搜索时没有结果不算
    private func emptySignalID(matchCount: Int) -> String? {
        guard matchCount == 0, !session.selectedStuff.isEmpty, session.keyword.isEmpty else {
            return nil
        }
        return ([session.tool ?? "", session.mode.rawValue] + session.selectedStuff.sorted()).joined(separator: "|")
    }

    private func addSuggestion(_ name: String) {
        withAnimation(.snappy) {
            session.addStuff(name)
            notice = nil
        }
        input = ""
    }

    /// 回车时把一整句识别成筛选条件；只列了几样食材时和点选一样，不额外提示（和 Web 的 IngredientInput 一致）
    private func applySentence() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return
        }
        let parsed = store.queryParser.parse(text)
        if parsed.isEmpty {
            show(InputNotice(kind: .notRecognized, message: "没认出食材，换个说法试试，如：土豆、鸡蛋，不要辣"))
            return
        }
        let names = parsed.stuff + parsed.unknown
        var rest = parsed
        rest.stuff = []
        rest.unknown = []
        let plainList = store.data.normalizer.unique([text]).allSatisfy(names.contains)
        let onlyStuff = !names.isEmpty && rest.isEmpty
        let nothingNew = onlyStuff && names.allSatisfy(session.selectedStuff.contains)

        withAnimation(.snappy) {
            session.apply(parsed, normalizer: store.data.normalizer)
        }
        input = ""
        isInputPresented = false
        if nothingNew {
            show(InputNotice(kind: .info, message: "已经选过了"))
        } else if !(plainList && onlyStuff) {
            show(InputNotice(kind: .applied, message: "已按「\(store.describe(parsed))」筛选"))
        } else {
            withAnimation(.snappy) { notice = nil }
        }
    }

    private func show(_ notice: InputNotice) {
        withAnimation(.snappy) {
            self.notice = notice
        }
        AccessibilityNotification.Announcement(notice.message).post()
    }

    @ViewBuilder
    private func stuffSection(_ title: String, _ items: [StuffItem]) -> some View {
        let count = items.filter { session.selectedStuff.contains($0.name) }.count
        ChipSection(title: title, note: count > 0 ? "已选 \(count) 种" : nil) {
            ForEach(items, id: \.name) { item in
                Chip(
                    title: item.name,
                    emoji: item.emoji,
                    isSelected: session.selectedStuff.contains(item.name),
                    onTap: { launchFlight(item, from: $0) }
                ) {
                    session.toggleStuff(item.name)
                }
            }
        }
    }
}

/// 输入框下方的一行提示：识别出了什么、没认出来，或者已经选过
struct InputNotice: Hashable {
    enum Kind {
        case applied
        case notRecognized
        case info
    }

    let id = UUID()
    let kind: Kind
    let message: String
}

private struct InputNoticeRow: View {
    let notice: InputNotice
    let onClose: () -> Void

    var body: some View {
        Section {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Image(systemName: symbol)
                    .foregroundStyle(tint)
                    .symbolEffect(.bounce, value: notice.id)
                Text(notice.message)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onClose) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("关闭提示")
            }
            .accessibilityElement(children: .combine)
        }
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    private var symbol: String {
        switch notice.kind {
        case .applied: return "text.badge.checkmark"
        case .notRecognized: return "questionmark.bubble"
        case .info: return "checkmark.circle"
        }
    }

    private var tint: Color {
        switch notice.kind {
        case .applied: return .accentColor
        case .notRecognized: return .orange
        case .info: return .secondary
        }
    }
}

/// 底部按钮里叠在一起的几样食材，选中时一个个弹进来
private struct BasketStack: View {
    struct Item: Hashable {
        let name: String
        let emoji: String
    }

    let items: [Item]

    var body: some View {
        HStack(spacing: -10) {
            ForEach(Array(items.enumerated()), id: \.element.name) { index, item in
                Text(item.emoji)
                    .font(.body)
                    .frame(width: 30, height: 30)
                    .background(.background, in: Circle())
                    .overlay(Circle().strokeBorder(.white.opacity(0.6), lineWidth: 1))
                    .zIndex(Double(index))
                    .transition(.scale(scale: 0.3).combined(with: .opacity))
            }
        }
        .animation(.bouncy(duration: 0.35), value: items)
        .accessibilityHidden(true)
    }
}

struct ResultsRoute: Hashable {}

/// 筛选：做法、难度、不吃辣，以及家里常备和忌口
struct FilterSheet: View {
    @Environment(CookSession.self) private var session
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ChipSection(title: "做法", note: session.methodGroups.isEmpty ? nil : "只看这些做法") {
                    ForEach(Recommender.methodGroups, id: \.label) { group in
                        Chip(title: group.label, isSelected: session.methodGroups.contains(group.label)) {
                            session.toggleMethodGroup(group.label)
                        }
                    }
                }
                ChipSection(title: "难度", note: session.difficulty.isEmpty ? nil : "没标难度的菜会被隐藏") {
                    ForEach(Recommender.difficultyLevels, id: \.self) { level in
                        Chip(title: level, isSelected: session.difficulty.contains(level)) {
                            session.toggleDifficulty(level)
                        }
                    }
                }
                Section {
                    PreferenceRows()
                } header: {
                    Text("调料和忌口")
                } footer: {
                    Text("家里常备、忌口和不吃辣会记住，下次打开还在。")
                }
            }
            .navigationTitle("筛选")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("清除") { session.clearFilters() }
                        .disabled(session.activeFilterCount == 0)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .sensoryFeedback(.selection, trigger: session.activeFilterCount)
    }
}

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
