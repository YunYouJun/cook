import CookCore
import SwiftData
import SwiftUI
import UIKit

/// 冰箱：记下家里有哪些食材、什么时候到期，快过期的排在前面，一键拿去找菜；可以开临期提醒
struct FridgeView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.modelContext) private var context
    @Environment(AppRouter.self) private var router
    @Query(filter: FridgeItem.inStock, sort: \FridgeItem.expiresAt) private var items: [FridgeItem]
    @Query(sort: \FridgeCategory.name) private var categories: [FridgeCategory]
    @AppStorage(FridgeReminder.enabledKey) private var remindersEnabled = false
    @AppStorage(FridgeReminder.promptDismissedKey) private var promptDismissed = false
    @State private var path = NavigationPath()
    @State private var sheet: Sheet?
    @State private var grouping = Grouping.expiry
    @State private var now = Date()
    @State private var saveError = false

    private enum Grouping { case expiry, category }
    private enum Route: Hashable { case history }

    private enum Sheet: Identifiable {
        case manual, photo, categories, edit(FridgeItem)

        enum ID: Hashable { case manual, photo, categories, edit(PersistentIdentifier) }

        var id: ID {
            switch self {
            case .manual: return .manual
            case .photo: return .photo
            case .categories: return .categories
            case let .edit(item): return .edit(item.persistentModelID)
            }
        }
    }

    var body: some View {
        let groups = Dictionary(grouping: items) { item -> Int in
            let freshness = Freshness(expiresAt: item.expiryDay(), now: now)
            return freshness.isExpired ? 2 : freshness.needsAttention ? 0 : 1
        }
        let soon = groups[0] ?? []
        let fresh = groups[1] ?? []
        let expired = groups[2] ?? []
        let usable = soon + fresh
        NavigationStack(path: $path) {
            Group {
                if items.isEmpty {
                    ContentUnavailableView {
                        Label("冰箱是空的", systemImage: "refrigerator")
                    } description: {
                        Text("把买回来的食材记在这里，快过期时提醒你，还能直接看看用它们能做什么。")
                    } actions: {
                        Button("放点食材") { sheet = .manual }
                            .buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        if !remindersEnabled, !promptDismissed {
                            ReminderPrompt(onDismiss: { promptDismissed = true })
                        }
                        Picker("冰箱分组", selection: $grouping) {
                            Text("按临期").tag(Grouping.expiry)
                            Text("按分类").tag(Grouping.category)
                        }
                        .pickerStyle(.segmented)
                        if grouping == .expiry {
                            expirySections(soon: soon, fresh: fresh, expired: expired)
                        } else {
                            categorySections
                        }
                        Section {
                            FridgeReminderRows()
                        } header: {
                            Text("临期提醒")
                        } footer: {
                            Text("食用建议与包装日期会分别标明，点开食材可以修改。提醒只在这台设备上排，不经过服务器。")
                        }
                    }
                    .bottomActionBar {
                        Button {
                            cook(usable)
                        } label: {
                            Text(usable.isEmpty ? "先核对冰箱里的食材" : "用冰箱里的做菜（\(uniqueStrings(usable.map(\.name)).count) 样）")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                        }
                        .prominentButtonStyle()
                        .controlSize(.large)
                        .disabled(usable.isEmpty)
                    }
                }
            }
            .navigationTitle("冰箱")
            .fridgeSaveError(isPresented: $saveError)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("冰箱历史", systemImage: "clock.arrow.circlepath") { path.append(Route.history) }
                        .accessibilityIdentifier("fridge.history")
                    Button("管理分类", systemImage: "folder") { sheet = .categories }
                    Button { sheet = .photo } label: {
                        Label("拍照识别", systemImage: "camera")
                    }
                    .accessibilityIdentifier("fridge.photo.open")
                    Button {
                        sheet = .manual
                    } label: {
                        Label("放进冰箱", systemImage: "plus")
                    }
                }
            }
            .navigationDestination(for: ResultsRoute.self) { _ in
                ResultsView()
            }
            .navigationDestination(for: Route.self) { _ in
                FridgeHistoryView()
            }
            .recipeDestinations()
            .sheet(item: $sheet) { route in
                switch route {
                case .manual: AddFridgeSheet()
                case .photo: FridgePhotoSheet()
                case .categories: FridgeCategoryManager()
                case let .edit(item): FridgeItemSheet(item: item)
                }
            }
        }
        .sensoryFeedback(.success, trigger: items.count) { old, new in new > old }
        .refreshesAtMidnight($now)
        // 点开临期通知：不管之前点进了哪里，都回到冰箱首页看临期列表
        .onChange(of: router.fridgeHomeRequests) {
            path = NavigationPath()
            sheet = nil
            grouping = .expiry
        }
    }

    @ViewBuilder
    private func expirySections(soon: [FridgeItem], fresh: [FridgeItem], expired: [FridgeItem]) -> some View {
        if !soon.isEmpty {
            Section {
                rows(soon)
            } header: {
                HStack {
                    Text("优先食用")
                    Spacer()
                    Button("只用这几样做菜") { cook(soon) }
                        .font(.footnote)
                        .textCase(nil)
                }
            } footer: {
                Text("记录日期在今天到后天之间，先安排食用。")
            }
        }
        if !fresh.isEmpty {
            Section("稍后食用") { rows(fresh) }
        }
        if !expired.isEmpty {
            Section {
                rows(expired)
            } header: {
                Text("已过记录日期")
            } footer: {
                Text("请核对包装及储存情况；外观和气味不能保证食用安全。左滑可以移出冰箱。")
            }
        }
    }

    private var categorySections: some View {
        let food = store.food
        let groups = Dictionary(grouping: items) { item in
            FridgeCategoryChoice(storedKey: item.categoryKey).resolved(for: item.name, in: food, custom: categories)
        }
        return Group {
            ForEach(FridgeBuiltInCategory.allCases.filter { $0 != .uncategorized }, id: \.self) { category in
                categorySection(category.title, items: groups[.builtIn(category)])
            }
            ForEach(categories) { category in
                categorySection(category.name, items: groups[.custom(category.identifier)])
            }
            categorySection(FridgeBuiltInCategory.uncategorized.title, items: groups[.builtIn(.uncategorized)])
        }
    }

    @ViewBuilder
    private func categorySection(_ title: String, items: [FridgeItem]?) -> some View {
        if let items { Section(title) { rows(items) } }
    }

    private func rows(_ list: [FridgeItem]) -> some View {
        ForEach(list) { item in
            Button {
                sheet = .edit(item)
            } label: {
                FridgeRow(item: item, emoji: emoji(for: item.name), freshness: Freshness(expiresAt: item.expiryDay(), now: now))
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                Button("吃完了", systemImage: "checkmark") {
                    changeStock { try context.markFridgeItemConsumed(item) }
                }
                .tint(.green)
                Button("删除", systemImage: "trash", role: .destructive) {
                    changeStock { try context.deleteFridgeItem(item) }
                }
            }
        }
    }

    private func changeStock(_ change: () throws -> Void) {
        do {
            try withAnimation { try change() }
        } catch {
            saveError = true
        }
    }

    private func emoji(for name: String) -> String {
        FridgeFruit(rawValue: name)?.emoji ?? store.emoji(forStuff: name) ?? "🥡"
    }

    /// 把这几样食材带去找菜，在冰箱页里直接打开推荐结果
    private func cook(_ list: [FridgeItem]) {
        guard !list.isEmpty else {
            return
        }
        session.use(list.map(\.name))
        path.append(ResultsRoute())
    }
}

private struct FridgeHistoryView: View {
    @Environment(\.modelContext) private var context
    @Query(filter: FridgeItem.inHistory, sort: \FridgeItem.consumedAt, order: .reverse) private var items: [FridgeItem]
    @State private var saveError = false

    var body: some View {
        Group {
            if items.isEmpty {
                ContentUnavailableView("还没有吃完的食材", systemImage: "clock.arrow.circlepath",
                                       description: Text("标记「吃完了」后，可以在这里把食材恢复到冰箱。"))
            } else {
                List {
                    Section {
                        ForEach(items) { item in
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(item.name)
                                        .font(.headline)
                                    if let consumedAt = item.consumedAt {
                                        Text("吃完：\(consumedAt.formatted(date: .abbreviated, time: .shortened))")
                                            .font(.subheadline)
                                            .foregroundStyle(.secondary)
                                    }
                                    Text("放入：\(item.addedAt.formatted(date: .abbreviated, time: .omitted))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                .accessibilityElement(children: .combine)
                                Spacer(minLength: 0)
                                Button("恢复") { restore(item) }
                                    .buttonStyle(.bordered)
                                    .accessibilityLabel("恢复\(item.name)")
                            }
                        }
                    } footer: {
                        Text("恢复后保留原来的放入日期、到期日和分类。")
                    }
                }
            }
        }
        .navigationTitle("冰箱历史")
        .navigationBarTitleDisplayMode(.inline)
        .fridgeSaveError(isPresented: $saveError)
    }

    private func restore(_ item: FridgeItem) {
        do {
            try withAnimation { try context.restoreFridgeItem(item) }
        } catch {
            saveError = true
        }
    }
}

/// 冰箱里的一行：食材、放入日期和离到期还有几天
private struct FridgeRow: View {
    let item: FridgeItem
    let emoji: String
    let freshness: Freshness

    var body: some View {
        HStack(spacing: 12) {
            Text(emoji)
                .font(.title2)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name)
                    .foregroundStyle(Color.primary)
                Text("\(item.addedAt.formatted(.dateTime.month().day()))放入")
                    .font(.caption)
                    .foregroundStyle(Color.secondary)
                Text(item.expiryBasis.title)
                    .font(.caption2)
                    .foregroundStyle(Color.secondary)
            }
            Spacer()
            Text(freshness.label(isSuggested: item.expiryBasis.isSuggested))
                .font(.subheadline)
                .monospacedDigit()
                .foregroundStyle(tint)
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint("点开修改日期或分类")
    }

    private var tint: Color {
        switch freshness {
        case .expired: return .red
        case .today, .soon: return .orange
        case .fresh: return .secondary
        }
    }
}

/// 冰箱页顶部的一张卡片：还没开提醒时问一次
private struct ReminderPrompt: View {
    let onDismiss: () -> Void
    @AppStorage(FridgeReminder.enabledKey) private var remindersEnabled = false
    @State private var denied = false

    var body: some View {
        Section {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: "bell.badge")
                    .font(.title2)
                    .foregroundStyle(.tint)
                VStack(alignment: .leading, spacing: 8) {
                    Text("开启临期提醒")
                        .font(.headline)
                    Text("在记录日期前一天和当天各提醒一次，同一天的合成一条。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Button("开启") {
                        Task {
                            if await FridgeReminder.requestAuthorization() {
                                remindersEnabled = true
                            } else {
                                denied = true
                            }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                }
                Spacer(minLength: 0)
                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("不用了")
            }
            .padding(.vertical, 4)
        }
        .notificationsDeniedAlert(isPresented: $denied)
    }
}

/// 临期提醒的开关和时间，冰箱页和「我的」共用
struct FridgeReminderRows: View {
    @AppStorage(FridgeReminder.enabledKey) private var remindersEnabled = false
    @AppStorage(FridgeReminder.timeKey) private var minutes = FridgeReminder.defaultMinutes
    @State private var denied = false

    var body: some View {
        Toggle("临期提醒", isOn: Binding(get: { remindersEnabled }, set: setEnabled))
            .notificationsDeniedAlert(isPresented: $denied)
        if remindersEnabled {
            DatePicker("每天提醒时间", selection: time, displayedComponents: .hourAndMinute)
        }
    }

    private var time: Binding<Date> {
        Binding {
            Calendar.current.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: Date()) ?? Date()
        } set: { date in
            let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
            minutes = (parts.hour ?? 9) * 60 + (parts.minute ?? 0)
        }
    }

    private func setEnabled(_ value: Bool) {
        guard value else {
            remindersEnabled = false
            return
        }
        Task {
            if await FridgeReminder.requestAuthorization() {
                remindersEnabled = true
            } else {
                denied = true
            }
        }
    }
}

extension View {
    /// 页面开着跨过零点、改了时区或回到前台时更新「现在」，到期天数和分组跟着变
    func refreshesAtMidnight(_ now: Binding<Date>) -> some View {
        modifier(MidnightRefresh(now: now))
    }
}

private struct MidnightRefresh: ViewModifier {
    @Binding var now: Date
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.significantTimeChangeNotification)) { _ in
                now = Date()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    now = Date()
                }
            }
    }
}

private extension View {
    /// 通知权限被拒过，只能去系统设置里打开
    func notificationsDeniedAlert(isPresented: Binding<Bool>) -> some View {
        alert("通知没有打开", isPresented: isPresented) {
            Button("取消", role: .cancel) {}
            Button("去设置") {
                if let url = URL(string: UIApplication.openNotificationSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
        } message: {
            Text("在系统设置里允许云食册发送通知，才能收到临期提醒。")
        }
    }
}

/// 放进冰箱：从食材词典点选，词典里没有的可以自己输入；到期日按默认保质期估
struct AddFridgeSheet: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var selected: [String] = []
    @State private var input = ""
    @State private var category = FridgeCategoryChoice.automatic
    @State private var saveError = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField("词典里没有的，比如：秋葵", text: $input)
                            .onSubmit(addInput)
                            .submitLabel(.done)
                        Button("添加", action: addInput)
                            .disabled(input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                Section {
                    FridgeCategoryPicker(selection: $category)
                } footer: {
                    Text("自动归类会按每种食材分别归类。指定分类后，本次选中的食材会放进同一分类。")
                }
                if !extras.isEmpty {
                    ChipSection(title: "输入的食材") {
                        ForEach(extras, id: \.self) { name in
                            Chip(title: name, isSelected: true) { toggle(name) }
                        }
                    }
                }
                ChipSection(title: "水果") {
                    ForEach(FridgeFruit.allCases) { fruit in
                        Chip(title: fruit.rawValue, emoji: fruit.emoji, isSelected: selected.contains(fruit.rawValue)) {
                            toggle(fruit.rawValue)
                        }
                    }
                }
                section("蔬菜", store.food.vegetable)
                section("肉和蛋", store.food.meat)
                section("主食", store.food.staple)
                if !selected.isEmpty {
                    Section {
                        ForEach(selected, id: \.self) { name in
                            if let advice = ProduceStorageAdvice.recommendation(for: name) {
                                LabeledContent(name, value: "建议 \(advice.days) 天内吃完")
                            } else {
                                LabeledContent(name, value: "通用参考：\(store.data.shelfLife.days(for: name)) 天")
                            }
                        }
                    } header: {
                        Text("食用时间建议")
                    } footer: {
                        Text("从今天购买起算，已核对的蔬果按完整冷藏建议安排，其他条目为通用估算。包装食品以包装说明为准。拍照录入可调整购买日期和切开状态。")
                    }
                }
            }
            .navigationTitle("放进冰箱")
            .navigationBarTitleDisplayMode(.inline)
            .fridgeSaveError(isPresented: $saveError)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(selected.isEmpty ? "放进去" : "放进去（\(selected.count)）", action: save)
                        .disabled(selected.isEmpty)
                }
            }
        }
        .sensoryFeedback(.selection, trigger: selected)
    }

    private var extras: [String] {
        selected.filter { !store.isCatalogStuff($0) && FridgeFruit(rawValue: $0) == nil }
    }

    private func section(_ title: String, _ list: [StuffItem]) -> some View {
        ChipSection(title: title) {
            ForEach(list.filter { FridgeFruit(rawValue: $0.name) == nil }, id: \.name) { item in
                Chip(title: item.name, emoji: item.emoji, isSelected: selected.contains(item.name)) {
                    toggle(item.name)
                }
                .accessibilityHint("建议 \(ProduceStorageAdvice.recommendation(for: item.name)?.days ?? store.data.shelfLife.days(for: item.name)) 天内吃完")
            }
        }
    }

    private func toggle(_ name: String) {
        selected = CookSession.toggled(selected, name)
    }

    /// 输入的按别名归一（西红柿 → 番茄），词典里有的就当作点选
    private func addInput() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return
        }
        let names = store.data.normalizer.unique(text.split(whereSeparator: { "，,、 ".contains($0) }).map(String.init))
        selected = uniqueStrings(selected + names)
        input = ""
    }

    private func save() {
        do {
            try context.addEstimatedFridgeItems(selected, shelfLife: store.data.shelfLife, categoryKey: category.storedKey)
            dismiss()
        } catch {
            saveError = true
        }
    }
}

/// 点开冰箱里的一样食材：改到期日、标记吃完或删除
private struct FridgeItemSheet: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    let item: FridgeItem
    @State private var expiresAt: Date
    @State private var basis: FridgeExpiryBasis
    @State private var category: FridgeCategoryChoice
    @State private var saveError = false

    init(item: FridgeItem) {
        self.item = item
        _expiresAt = State(initialValue: item.expiryDay())
        _basis = State(initialValue: item.expiryBasis)
        _category = State(initialValue: FridgeCategoryChoice(storedKey: item.categoryKey))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    FridgeCategoryPicker(selection: $category, automaticTitle: FridgeBuiltInCategory.automatic(for: item.name, in: store.food).title)
                }
                Section {
                    LabeledContent("放入日期", value: item.addedAt.formatted(.dateTime.year().month().day()))
                    if let production = item.productionDate {
                        LabeledContent("生产日期", value: FridgeDay.local(production).formatted(date: .numeric, time: .omitted))
                    }
                    Picker("日期依据", selection: $basis) {
                        ForEach(FridgeExpiryBasis.allCases) { Text($0.title).tag($0) }
                    }
                    DatePicker(basis.isSuggested ? "建议吃完日期" : "到期日", selection: $expiresAt, displayedComponents: .date)
                    LabeledContent("状态", value: Freshness(expiresAt: expiresAt).label(isSuggested: basis.isSuggested))
                } footer: {
                    Text(item.storageAdvice ?? "食用日期仅供安排食用，请核对包装日期和储存条件。")
                }
                Section {
                    Button("吃完了，移出冰箱") {
                        changeStock { try context.markFridgeItemConsumed(item) }
                    }
                    Button("删除食材", role: .destructive) {
                        changeStock { try context.deleteFridgeItem(item) }
                    }
                } footer: {
                    Text("吃完的食材可以在冰箱历史中恢复。删除的食材不会进入历史。")
                }
            }
            .navigationTitle(item.name)
            .navigationBarTitleDisplayMode(.inline)
            .fridgeSaveError(isPresented: $saveError)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成", action: save)
                }
            }
        }
    }

    private func changeStock(_ change: () throws -> Void) {
        do {
            try change()
            dismiss()
        } catch {
            saveError = true
        }
    }

    private func save() {
        do {
            try context.saveUserChange {
                let expiresDay = FridgeDay.stored(expiresAt)
                let changedDate = expiresDay != item.expiresAt
                let editedBasis = changedDate && (basis == .estimated || basis == .calculated) ? .manual : basis
                if changedDate || editedBasis != item.expiryBasis { item.storageAdvice = nil }
                item.expiryBasisRaw = editedBasis.rawValue
                item.expiresAt = expiresDay
                item.categoryKey = category.storedKey
                item.updatedAt = Date()
            }
            dismiss()
        } catch {
            saveError = true
        }
    }
}
