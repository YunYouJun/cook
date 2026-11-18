import CookCore
import SwiftUI

/// Progressive disclosure shared by catalog and private recipe details.
struct CookedRecipeRecordRow: View {
    @Environment(CookedRecipeRecords.self) private var records
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var celebration: LearningConfirmation?
    @State private var notice: String?
    @State private var error: String?
    let recipeID: CookingRecipeID
    let recipeName: String

    var body: some View {
        TimelineView(.periodic(from: Calendar.current.startOfDay(for: .now), by: 60)) { timeline in
            let cooked = records.cookedRecords(for: recipeID)
            let today = records.todayRecord(for: recipeID, at: timeline.date)
            VStack(alignment: .leading, spacing: 8) {
                if let learned = records.learnedRecord(for: recipeID) {
                    adaptiveActions {
                        Menu {
                            footprintLink
                            Button("撤销「学会」标记", role: .destructive) {
                                perform { try records.removeLearned(recipeID) }
                            }
                        } label: {
                            Text("✨ 已学会 · \(CookingDates.plaque(learned.learnedAt))")
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(Color.primary)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        .accessibilityIdentifier("recipe.learnedStatus")
                        if today == nil {
                            Button("今天又做了一次", action: recordCooked)
                                .accessibilityIdentifier("recipe.cookAgain")
                        }
                    }
                } else if !cooked.isEmpty {
                    adaptiveActions {
                        Menu {
                            Button(today == nil ? "今天又做了一次" : "我做过这道", action: recordCooked)
                            if let target = today ?? cooked.first {
                                Button(today == nil ? "撤销上次做过记录" : "撤销今天的做过记录", role: .destructive) {
                                    perform { try records.removeCooked(target.id) }
                                }
                            }
                            footprintLink
                        } label: {
                            Label("已做过（\(cooked.count) 次）", systemImage: "checkmark.circle.fill")
                                .foregroundStyle(Color.primary)
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        .accessibilityIdentifier("recipe.cookedRecord")
                        Button("记为学会") {
                            perform {
                                if let confirmation = try records.confirmLearned(recipeID, name: recipeName) {
                                    celebration = confirmation
                                }
                            }
                        }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .accessibilityIdentifier("recipe.markLearned")
                    }
                } else {
                    Button(action: recordCooked) {
                        Label("我做过这道", systemImage: "checkmark.circle")
                    }
                    .accessibilityIdentifier("recipe.cookedRecord")
                }
                if let notice {
                    Text(notice)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("cooking.notice")
                }
            }
            .buttonStyle(.borderless)
        }
        .onChange(of: recipeID) {
            notice = nil
        }
        .fullScreenCover(item: $celebration) { confirmation in
            CookingCelebrationView(confirmation: confirmation)
                .presentationBackground(.clear)
        }
        .alert("未能保存足迹", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(error ?? "") }
    }

    @ViewBuilder
    private func adaptiveActions<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 12, content: content)
        } else {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12, content: content)
                VStack(alignment: .leading, spacing: 12, content: content)
            }
        }
    }

    private var footprintLink: some View {
        NavigationLink("在做菜足迹中查看") { CookingFootprintView(recipeID: recipeID) }
    }

    private func recordCooked() {
        perform {
            let added = try records.recordCooked(recipeID, name: recipeName)
            notice = added == nil ? "今天已记过做过这道啦" : nil
        }
    }

    private func perform(_ action: () throws -> Void) {
        notice = nil
        do { try action() }
        catch { self.error = error.localizedDescription }
    }
}
