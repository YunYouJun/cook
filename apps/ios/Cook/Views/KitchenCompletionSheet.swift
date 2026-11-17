import CookCore
import SwiftUI

struct KitchenCompletionSheet: View {
    @Environment(CookedRecipeRecords.self) private var records
    @Environment(\.dismiss) private var dismiss
    @State private var celebration: LearningConfirmation?
    @State private var error: String?
    @Binding var notice: String?
    let recipe: Recipe

    private var isLearned: Bool { records.learnedRecord(for: .catalog(recipe.id)) != nil }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: "frying.pan")
                    .font(.largeTitle)
                    .foregroundStyle(.orange)
                    .accessibilityHidden(true)
                Text(isLearned ? "又做了一次「\(recipe.name)」" : "料理完成了吗？")
                    .font(.title2.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text("记录这次下厨时光")
                    .foregroundStyle(.secondary)
                if !isLearned {
                    Button("今天学会了！") {
                        do {
                            if let confirmation = try records.confirmLearned(.catalog(recipe.id), name: recipe.name) {
                                celebration = confirmation
                            }
                        }
                        catch { self.error = error.localizedDescription }
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .accessibilityIdentifier("kitchen.completion.learn")
                }
                Button(isLearned ? "记为做过" : "只记为做过") {
                    do {
                        let added = try records.recordCooked(.catalog(recipe.id), name: recipe.name)
                        notice = added == nil ? "今天已记过做过这道啦" : "已记下这次下厨时光"
                        dismiss()
                    } catch { self.error = error.localizedDescription }
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .accessibilityIdentifier("kitchen.completion.cook")
                Button("跳过", role: .cancel) { dismiss() }
                    .foregroundStyle(.secondary)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("kitchen.completion.skip")
            }
            .frame(maxWidth: .infinity)
            .padding(28)
        }
        .scrollBounceBehavior(.basedOnSize)
        .tint(CookPalette.primaryAction)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .fullScreenCover(item: $celebration, onDismiss: { dismiss() }) { confirmation in
            CookingCelebrationView(confirmation: confirmation)
                .presentationBackground(.clear)
        }
        .alert("未能保存足迹", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(error ?? "") }
        .accessibilityIdentifier("kitchen.completion")
    }
}
