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
        Label("饮食目标", systemImage: "fork.knife.circle")
            .foregroundStyle(Color.primary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var selectionValue: some View {
        Text(session.dietaryGoal?.title ?? "不限")
            .foregroundStyle(Color.primary)
            .fixedSize(horizontal: false, vertical: true)
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
                    if let message = store.dietaryUnavailableMessage(for: session.dietaryGoal) {
                        Text(message).foregroundStyle(Color.primary)
                    } else {
                        Text("只展示对应目标已核对的图文做法，也可以继续选择食材和厨具。").foregroundStyle(Color.primary)
                    }
                }

                if let goal = session.dietaryGoal {
                    Section {
                        Text(goal.explanation)
                            .font(.subheadline)
                            .foregroundStyle(Color.primary)
                            .fixedSize(horizontal: false, vertical: true)
                    } header: { Text("\(goal.title)的含义").foregroundStyle(Color.primary) }
                }

                Section {
                    Toggle(isOn: Binding(
                        get: { session.remembersDietaryGoal },
                        set: { session.rememberDietaryGoal($0) }
                    )) {
                        Text("记住这个目标")
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .disabled(session.dietaryGoal == nil && !session.remembersDietaryGoal)
                    .accessibilityIdentifier("dietary.remember")
                } footer: {
                    if let goal = session.rememberedDietaryGoal {
                        Text("下次默认选择“\(goal.title)”。仅保存在这台设备，选择“不限目标”会取消记忆。").foregroundStyle(Color.primary)
                    } else {
                        Text("默认只用于本次做菜。开启后，仅在这台设备记住你的选择。").foregroundStyle(Color.primary)
                    }
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
        .sensoryFeedback(.selection, trigger: session.dietaryGoal)
    }

    @ViewBuilder
    private var confirmationButton: some View {
        if #available(iOS 26.0, *) {
            Button(role: .confirm) { dismiss() }
                .accessibilityLabel("完成")
                .accessibilityIdentifier("dietary.done")
        } else {
            Button("完成") { dismiss() }
                .foregroundStyle(Color.primary)
                .accessibilityIdentifier("dietary.done")
        }
    }

    private func choice(_ goal: DietaryGoal?) -> some View {
        Button { session.selectDietaryGoal(goal) } label: {
            HStack(alignment: .center, spacing: 12) {
                Image(systemName: goal?.symbol ?? "fork.knife")
                    .foregroundStyle(.tint)
                    .frame(width: 24)
                    .accessibilityHidden(true)
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
            Label(goal.title, systemImage: goal.symbol)
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
