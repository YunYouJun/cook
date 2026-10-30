import CookCore
import SwiftData
import SwiftUI

/// The same native selection sheet is reachable from both cooking presentations and results.
struct DietaryGoalPickerButton: View {
    @Environment(CookSession.self) private var session
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Binding var isPresented: Bool

    var body: some View {
        Button { isPresented = true } label: {
            HStack {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 4) {
                        selectionTitle
                        selectionValue
                    }
                    Spacer(minLength: 12)
                } else {
                    selectionTitle
                    Spacer(minLength: 12)
                    selectionValue
                }
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .frame(minHeight: 32)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("饮食目标")
        .accessibilityIdentifier("dietary.openPicker")
        .accessibilityValue(session.dietaryGoal?.title ?? "不限目标")
    }

    private var selectionTitle: some View {
        Label {
            Text("饮食目标")
                .foregroundStyle(Color.primary)
        } icon: {
            DietaryGoalIcon(goal: session.dietaryGoal)
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private var selectionValue: some View {
        Text(session.dietaryGoal?.title ?? "不限")
            .foregroundStyle(Color.primary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Introduce the optional filter once without interrupting ingredient selection.
struct DietaryGoalIntroduction: View {
    @Environment(CookSession.self) private var session
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Binding var isPresented: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("这餐想怎样吃？").font(.headline)
            Text("可以先选一个饮食目标，也可以直接按食材找菜。")
                .font(.subheadline).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 0) { actions }
            } else {
                HStack(spacing: 20) { actions }
            }
            Text("之后可在「筛选 → 饮食目标」中修改。")
                .font(.footnote).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("dietary.introduction")
    }

    @ViewBuilder private var actions: some View {
        Button("选择饮食目标") { isPresented = true }
            .fontWeight(.semibold)
            .frame(minHeight: 44)
            .accessibilityIdentifier("dietary.introduction.choose")
        Button("暂不设置") { session.completeDietaryIntroduction() }
            .frame(minHeight: 44)
            .accessibilityIdentifier("dietary.introduction.skip")
    }
}

/// A selected filter stays visible without repeating the full preference row.
struct DietaryGoalSummaryButton: View {
    @Environment(CookSession.self) private var session
    @Binding var isPresented: Bool

    var body: some View {
        if let goal = session.dietaryGoal {
            Button { isPresented = true } label: {
                HStack(spacing: 8) {
                    DietaryGoalIcon(goal: goal)
                    Text(goal.title).foregroundStyle(Color.primary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                }
                .font(.subheadline)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
            }
            .accessibilityLabel("饮食目标")
            .accessibilityValue(goal.title)
            .accessibilityHint("修改饮食目标")
            .accessibilityIdentifier("dietary.openPicker")
        }
    }
}

struct DietaryGoalSelectionSheet: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        NavigationStack {
            List {
                Section {
                    choice(nil)
                    ForEach(store.dietaryGoals) { goal in choice(goal) }
                } header: {
                    Text("这一餐，想怎样安排？").foregroundStyle(Color.primary)
                } footer: {
                    VStack(alignment: .leading, spacing: 8) {
                        if let message = store.dietaryUnavailableMessage(for: session.dietaryGoal) {
                            Text(message)
                        } else {
                            Text("只展示对应目标已核对的图文做法，也可以继续选择食材和厨具。")
                        }
                        Text("选择后自动保存在这台设备，下次继续使用；选择“不限目标”可取消。")
                            .accessibilityIdentifier("dietary.persistenceNote")
                    }
                    .foregroundStyle(Color.primary)
                    .fixedSize(horizontal: false, vertical: true)
                }

                if let goal = session.dietaryGoal {
                    Section {
                        Text(goal.explanation)
                            .font(.subheadline)
                            .foregroundStyle(Color.primary)
                            .fixedSize(horizontal: false, vertical: true)
                    } header: { Text("\(goal.title)的含义").foregroundStyle(Color.primary) }
                }
            }
            // Rebuild cached list row heights when Dynamic Type changes on older iOS versions.
            .id(dynamicTypeSize)
            .navigationTitle("饮食目标")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    confirmationButton
                }
            }
        }
        // 明确整个弹窗的模态边界，辅助功能聚焦当前选择容器。
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .sensoryFeedback(.selection, trigger: session.dietaryGoal)
    }

    @ViewBuilder
    private var confirmationButton: some View {
        if #available(iOS 26.0, *) {
            Button(role: .confirm) { session.completeDietaryIntroduction(); dismiss() }
                .accessibilityLabel("完成")
                .accessibilityIdentifier("dietary.done")
        } else {
            Button("完成") { session.completeDietaryIntroduction(); dismiss() }
                .foregroundStyle(Color.primary)
                .accessibilityIdentifier("dietary.done")
        }
    }

    private func choice(_ goal: DietaryGoal?) -> some View {
        Button { session.selectDietaryGoal(goal) } label: {
            HStack(alignment: .center, spacing: 12) {
                DietaryGoalIcon(goal: goal)
                    .frame(width: 24)
                VStack(alignment: .leading, spacing: 4) {
                    Text(goal?.title ?? "不限目标")
                        .foregroundStyle(Color.primary)
                        .fixedSize(horizontal: false, vertical: true)
                    Text(goal?.subtitle ?? "按已有食材和厨具找菜")
                        .font(.subheadline)
                        .foregroundStyle(Color.primary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let goal, let catalog = store.dietaryCatalog {
                        Text("\(catalog.count(for: goal)) 道已核对做法")
                            .font(.caption)
                            .foregroundStyle(Color.primary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
                .layoutPriority(1)
                Spacer(minLength: 8)
                if session.dietaryGoal == goal {
                    Image(systemName: "checkmark")
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)
                }
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(goal?.title ?? "不限目标")
        .accessibilityValue(goal.map { "\(store.dietaryCatalog?.count(for: $0) ?? 0) 道已核对做法" } ?? "按已有食材和厨具找菜")
        .accessibilityHint(goal?.subtitle ?? "恢复普通推荐")
        .accessibilityAddTraits(session.dietaryGoal == goal ? .isSelected : [])
        .accessibilityIdentifier("dietary.goal.\(goal?.rawValue ?? "none")")
    }
}

/// Source facts remain visible next to the reviewed preparation, including the scope of each goal.
struct DietaryReviewSummary: View {
    let goal: DietaryGoal
    let entry: DietaryCatalog.Entry

    var body: some View {
        GroupBox {
            VStack(alignment: .leading, spacing: 10) {
                Text(entry.reason(for: goal))
                    .accessibilityIdentifier("dietary.reviewReason")
                if goal == .weightAware, let oil = entry.portionOil {
                    Text("按原文 \(oil.referenceServings) 份计算；添加油不等于总脂肪或吸收油量。")
                        .font(.footnote)
                        .foregroundStyle(Color.primary)
                }
                Text(goal.explanation)
                    .font(.footnote)
                    .foregroundStyle(Color.primary)
                Text("核对日期：\(entry.reviewedAt)")
                    .font(.caption)
                    .foregroundStyle(Color.primary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } label: {
            Label {
                Text(goal.title)
                    .foregroundStyle(Color.primary)
            } icon: {
                DietaryGoalIcon(goal: goal)
            }
        }
        .accessibilityIdentifier("dietary.reviewSummary")
    }
}

#Preview("饮食目标") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        let container = Persistence.makeContainer(inMemory: true)
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        DietaryGoalSelectionSheet()
            .environment(store)
            .environment(session)
            .modelContainer(container)
    }
}

#Preview("饮食目标 · 大字体与深色") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        let container = Persistence.makeContainer(inMemory: true)
        let session = CookSession(defaultPantry: [], context: container.mainContext)
        DietaryGoalSelectionSheet()
            .environment(store)
            .environment(session)
            .modelContainer(container)
            .environment(\.dynamicTypeSize, .accessibility3)
            .preferredColorScheme(.dark)
    }
}
