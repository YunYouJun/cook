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
    @Query(filter: #Predicate<FridgeItem> { $0.deletedAt == nil }, sort: \FridgeItem.expiresAt) private var items: [FridgeItem]
    @AppStorage(FridgeReminder.enabledKey) private var remindersEnabled = false
    @AppStorage(FridgeReminder.promptDismissedKey) private var promptDismissed = false
    @State private var path = NavigationPath()
    @State private var adding = false
    @State private var editing: FridgeItem?
    @State private var now = Date()

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
                        Button("放点食材") { adding = true }
                            .buttonStyle(.borderedProminent)
                    }
                } else {
                    List {
                        if !remindersEnabled, !promptDismissed {
                            ReminderPrompt(onDismiss: { promptDismissed = true })
                        }
                        if !soon.isEmpty {
                            Section {
                                rows(soon, now: now)
                            } header: {
                                HStack {
                                    Text("快过期")
                                    Spacer()
                                    Button("只用这几样做菜") { cook(soon) }
                                        .font(.footnote)
                                        .textCase(nil)
                                }
                            } footer: {
                                Text("今天到后天到期的，先用掉。")
                            }
                        }
                        if !fresh.isEmpty {
                            Section("还新鲜") {
                                rows(fresh, now: now)
                            }
                        }
                        if !expired.isEmpty {
                            Section {
                                rows(expired, now: now)
                            } header: {
                                Text("已过期")
                            } footer: {
                                Text("闻一闻、看一看再决定，不确定就别吃了。左滑可以移出冰箱。")
                            }
                        }
                        Section {
                            FridgeReminderRows()
                        } header: {
                            Text("临期提醒")
                        } footer: {
                            Text("到期日按常见的冷藏保存时间估算，点开食材可以改。提醒只在这台设备上排，不经过服务器。")
                        }
                    }
                    .bottomActionBar {
                        Button {
                            cook(usable)
                        } label: {
                            Text(usable.isEmpty ? "都过期了，先清理一下" : "用冰箱里的做菜（\(uniqueStrings(usable.map(\.name)).count) 样）")
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
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        adding = true
                    } label: {
                        Label("放进冰箱", systemImage: "plus")
                    }
                }
            }
            .navigationDestination(for: ResultsRoute.self) { _ in
                ResultsView()
            }
            .recipeDestinations()
            .sheet(isPresented: $adding) {
                AddFridgeSheet()
            }
            .sheet(item: $editing) { item in
                FridgeItemSheet(item: item)
                    .presentationDetents([.medium, .large])
            }
        }
        .sensoryFeedback(.success, trigger: items.count) { old, new in new > old }
        .refreshesAtMidnight($now)
        // 点开临期通知：不管之前点进了哪里，都回到冰箱首页看临期列表
        .onChange(of: router.fridgeHomeRequests) {
            path = NavigationPath()
            adding = false
            editing = nil
        }
    }

    private func rows(_ list: [FridgeItem], now: Date) -> some View {
        ForEach(list) { item in
            Button {
                editing = item
            } label: {
                FridgeRow(item: item, emoji: emoji(for: item.name), freshness: Freshness(expiresAt: item.expiryDay(), now: now))
            }
            .swipeActions(edge: .trailing) {
                Button("吃完了", systemImage: "checkmark") {
                    withAnimation { context.removeFromFridge([item]) }
                }
                .tint(.green)
            }
        }
    }

    private func emoji(for name: String) -> String {
        store.emoji(forStuff: name) ?? "🥡"
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
            }
            Spacer()
            Text(freshness.label)
                .font(.subheadline)
                .monospacedDigit()
                .foregroundStyle(tint)
        }
        .accessibilityElement(children: .combine)
        .accessibilityHint("点开修改到期日")
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
                    Text("食材到期前一天和当天各提醒一次，同一天的合成一条。")
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
                } footer: {
                    if !extras.isEmpty {
                        Text("自己输入的按 \(store.data.shelfLife.defaultDays) 天估到期日。")
                    }
                }
                if !extras.isEmpty {
                    ChipSection(title: "输入的食材") {
                        ForEach(extras, id: \.self) { name in
                            Chip(title: name, isSelected: true) { toggle(name) }
                        }
                    }
                }
                section("蔬菜", store.data.food.vegetable)
                section("肉和蛋", store.data.food.meat)
                section("主食", store.data.food.staple)
            }
            .navigationTitle("放进冰箱")
            .navigationBarTitleDisplayMode(.inline)
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
        selected.filter { !store.isCatalogStuff($0) }
    }

    private func section(_ title: String, _ list: [StuffItem]) -> some View {
        ChipSection(title: title) {
            ForEach(list, id: \.name) { item in
                Chip(title: item.name, emoji: item.emoji, isSelected: selected.contains(item.name)) {
                    toggle(item.name)
                }
                .accessibilityHint("冷藏约 \(store.data.shelfLife.days(for: item.name)) 天")
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
        let now = Date()
        for name in selected {
            context.addToFridge(name, days: store.data.shelfLife.days(for: name), at: now)
        }
        dismiss()
    }
}

/// 点开冰箱里的一样食材：改到期日，或者移出冰箱
private struct FridgeItemSheet: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    let item: FridgeItem
    @State private var expiresAt = Date()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("放入日期", value: item.addedAt.formatted(.dateTime.year().month().day()))
                    DatePicker("到期日", selection: $expiresAt, displayedComponents: .date)
                    LabeledContent("状态", value: Freshness(expiresAt: expiresAt).label)
                } footer: {
                    Text("默认按冷藏约 \(store.data.shelfLife.days(for: item.name)) 天估算，以包装上的日期和实际情况为准。")
                }
                Section {
                    Button("吃完了，移出冰箱", role: .destructive) {
                        context.removeFromFridge([item])
                        dismiss()
                    }
                }
            }
            .navigationTitle(item.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") {
                        context.setExpiry(item, to: expiresAt)
                        dismiss()
                    }
                }
            }
            .onAppear { expiresAt = item.expiryDay() }
        }
    }
}
