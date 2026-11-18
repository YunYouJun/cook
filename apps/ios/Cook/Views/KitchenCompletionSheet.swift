import CookCore
import SwiftUI

struct KitchenCompletionSheet: View {
    @Environment(CookedRecipeRecords.self) private var records
    @Environment(RecipeStore.self) private var store
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.dismiss) private var dismiss
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var celebration: LearningConfirmation?
    @State private var error: String?
    @State private var contentHeight: CGFloat?
    @Binding var notice: String?
    let recipe: Recipe

    private var isLearned: Bool { records.learnedRecord(for: .catalog(recipe.id)) != nil }
    private var alreadyCooked: Bool { records.todayRecord(for: .catalog(recipe.id)) != nil }

    private var detent: PresentationDetent {
        if verticalSizeClass == .compact || dynamicTypeSize.isAccessibilitySize { return .large }
        return .height(contentHeight ?? (isLearned ? 238 : 290))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text(isLearned ? "又做了一次" : "料理完成了吗？")
                    .font(.title2.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                recipeCard
                VStack(spacing: 8) {
                    if !isLearned {
                        Button {
                            do {
                                if let confirmation = try records.confirmLearned(.catalog(recipe.id), name: recipe.name) {
                                    celebration = confirmation
                                }
                            } catch {
                                self.error = error.localizedDescription
                            }
                        } label: {
                            actionLabel("今天学会了！", symbol: "sparkles")
                        }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("kitchen.completion.learn")
                    }
                    cookedButton
                    Button("跳过", role: .cancel) { dismiss() }
                        .buttonStyle(.plain)
                        .foregroundStyle(.secondary)
                        .frame(minHeight: CookLayout.minimumHitSize)
                        .accessibilityIdentifier("kitchen.completion.skip")
                }
                .buttonBorderShape(.capsule)
            }
            .frame(maxWidth: 420, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.top, 22)
            .padding(.bottom, 10)
            .frame(maxWidth: .infinity)
            // Measure the content inside the scroll view, independently of the current detent.
            .onGeometryChange(for: CGFloat.self) { proxy in
                ceil(proxy.size.height)
            } action: { height in
                if height > 0 { contentHeight = height }
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .tint(CookPalette.primaryAction)
        .presentationDetents([detent])
        .presentationDragIndicator(.visible)
        .presentationBackground(.background)
        .fullScreenCover(item: $celebration, onDismiss: { dismiss() }) { confirmation in
            CookingCelebrationView(confirmation: confirmation)
                .presentationBackground(.clear)
        }
        .alert("未能保存足迹", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(error ?? "") }
        .accessibilityIdentifier("kitchen.completion")
    }

    private var recipeCard: some View {
        let meta = store.meta(for: recipe)
        return HStack(spacing: 12) {
            if !dynamicTypeSize.isAccessibilitySize {
                Text(store.thumbnail(for: recipe))
                    .font(.title2)
                    .frame(width: 44, height: 44)
                    .background(CookingJournalPalette.warmFill, in: RoundedRectangle(cornerRadius: 12))
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(recipe.name).font(.headline)
                if !meta.isEmpty {
                    Text(meta)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(CookPalette.inputBackground, in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var cookedButton: some View {
        if isLearned && !alreadyCooked {
            Button(action: recordCooked) { actionLabel("记为做过", symbol: "checkmark") }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("kitchen.completion.cook")
        } else {
            Button(action: recordCooked) {
                actionLabel(alreadyCooked ? "今天已记录" : "只记为做过", symbol: alreadyCooked ? "checkmark" : "frying.pan")
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("kitchen.completion.cook")
        }
    }

    private func actionLabel(_ title: String, symbol: String) -> some View {
        Label(title, systemImage: symbol)
            .font(.body.weight(.semibold))
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity, minHeight: 32)
    }

    private func recordCooked() {
        do {
            let added = try records.recordCooked(.catalog(recipe.id), name: recipe.name)
            notice = added == nil ? "今天已记过做过这道啦" : "已记下这次下厨时光"
            dismiss()
        } catch {
            self.error = error.localizedDescription
        }
    }
}
