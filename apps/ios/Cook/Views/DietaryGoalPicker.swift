import CookCore
import SwiftData
import SwiftUI

/// The same native selection sheet is reachable from both cooking presentations and results.
struct DietaryGoalPickerButton: View {
    @Environment(CookSession.self) private var session
    @State private var sheet: Sheet?

    private enum Sheet: String, Identifiable { case goals; var id: String { rawValue } }

    var body: some View {
        Button { sheet = .goals } label: {
            HStack {
                Label("饮食目标", systemImage: "fork.knife.circle")
                    .foregroundStyle(.primary)
                Spacer(minLength: 12)
                Text(session.dietaryGoal?.title ?? "不限")
                    .foregroundStyle(.secondary)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .frame(minHeight: 32)
            .contentShape(Rectangle())
        }
        .accessibilityIdentifier("dietary.openPicker")
        .accessibilityValue(session.dietaryGoal?.title ?? "不限目标")
        .sheet(item: $sheet) { _ in DietaryGoalSelectionSheet() }
    }
}

struct DietaryGoalSelectionSheet: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    choice(nil)
                    ForEach(store.dietaryGoals) { goal in choice(goal) }
                } header: {
                    Text("这一餐，想怎样安排？")
                } footer: {
                    if let message = store.dietaryUnavailableMessage {
                        Text(message)
                    } else {
                        Text("只展示对应目标已核对的图文做法，也可以继续选择食材和厨具。")
                    }
                }

                if let goal = session.dietaryGoal {
                    Section {
                        Text(goal.explanation)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } header: { Text("\(goal.title)的含义") }
                }

                Section {
                    Toggle("记住这个目标", isOn: Binding(
                        get: { session.remembersDietaryGoal },
                        set: { session.rememberDietaryGoal($0) }
                    ))
                    .disabled(session.dietaryGoal == nil && !session.remembersDietaryGoal)
                    .accessibilityIdentifier("dietary.remember")
                } footer: {
                    if let goal = session.rememberedDietaryGoal {
                        Text("下次默认选择“\(goal.title)”。仅保存在这台设备，选择“不限目标”会取消记忆。")
                    } else {
                        Text("默认只用于本次做菜。开启后，仅在这台设备记住你的选择。")
                    }
                }
            }
            .navigationTitle("饮食目标")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                        .accessibilityIdentifier("dietary.done")
                }
            }
        }
        .sensoryFeedback(.selection, trigger: session.dietaryGoal)
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
                        .foregroundStyle(.primary)
                    Text(goal?.subtitle ?? "按已有食材和厨具找菜")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let goal, let catalog = store.dietaryCatalog {
                        Text("\(catalog.count(for: goal)) 道已核对做法")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
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
                        .foregroundStyle(.secondary)
                }
                Text(goal.explanation)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("核对日期：\(entry.reviewedAt)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
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
