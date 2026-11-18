import CookCore
import SwiftUI
import UIKit

struct CookingCelebrationView: View {
    @Environment(CookedRecipeRecords.self) private var records
    @Environment(RecipeStore.self) private var store
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss
    @State private var appeared = false
    @State private var sparklesExpanded = false
    @State private var error: String?
    @AccessibilityFocusState private var headingFocused: Bool
    let confirmation: LearningConfirmation

    var body: some View {
        ZStack {
            Color.black.opacity(0.25)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { dismiss() }
                .accessibilityLabel("收起庆祝卡片")
                .accessibilityAddTraits(.isButton)
            ScrollView {
                card
                    .contentShape(RoundedRectangle(cornerRadius: 28))
                    .onTapGesture {}
                    .opacity(appeared ? 1 : 0)
                    .scaleEffect(reduceMotion ? 1 : (appeared ? 1 : 0.96))
                    .offset(y: reduceMotion || appeared ? 0 : 18)
                    .padding(24)
                    .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
            .defaultScrollAnchor(.center)
        }
        .onTapGesture { dismiss() }
        .accessibilityIdentifier("cooking.celebration")
        .task {
            guard !appeared else { return }
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            withAnimation(reduceMotion ? .easeOut(duration: 0.3) : .spring(response: 0.45, dampingFraction: 0.8)) {
                appeared = true
            }
            if !reduceMotion {
                withAnimation(.easeOut(duration: 1.2)) { sparklesExpanded = true }
            }
            headingFocused = true
        }
        .alert("未能撤销", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(error ?? "") }
    }

    private var card: some View {
        let name = confirmation.learned.recipeName ?? "这道菜"
        return VStack(spacing: 20) {
            VStack(spacing: 8) {
                LearnedDishBadge(catalogRecipeID: badgeCatalogID, size: 96)
                    .padding(.bottom, 4)
                Label("今天学会了", systemImage: "sparkles")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(CookingJournalPalette.warmInk)
                Text(name)
                    .font(.system(.largeTitle, design: .serif).weight(.semibold))
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("今天学会了 · \(name)，获得菜肴徽章")
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("cooking.celebration.heading")
            .accessibilityFocused($headingFocused)
            .overlay {
                if !reduceMotion {
                    sparkles
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            Divider()
            VStack(spacing: 5) {
                Text(CookingDates.celebration(confirmation.learned.learnedAt))
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                Text("第 \(confirmation.learnedCount) 道拿手菜")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(CookingJournalPalette.warmInk)
            }
            VStack(spacing: 4) {
                Button { dismiss() } label: {
                    Text("太棒了").frame(maxWidth: .infinity, minHeight: 32)
                }
                .buttonStyle(.borderedProminent)
                .buttonBorderShape(.capsule)
                .tint(CookPalette.primaryAction)
                .accessibilityIdentifier("cooking.celebration.done")
                Button("点错了？撤销") {
                    do {
                        try records.undoConfirmation(confirmation)
                        dismiss()
                    } catch { self.error = error.localizedDescription }
                }
                .buttonStyle(.plain)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(minHeight: CookLayout.minimumHitSize)
                .accessibilityIdentifier("cooking.celebration.undo")
            }
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, 24)
        .padding(.top, 32)
        .padding(.bottom, 12)
        .frame(maxWidth: 400)
        .background {
            RoundedRectangle(cornerRadius: 28)
                .fill(.background)
                .overlay {
                    RoundedRectangle(cornerRadius: 28)
                        .fill(.linearGradient(colors: [CookingJournalPalette.warmFill, .clear],
                                              startPoint: .top, endPoint: .bottom))
                }
        }
        .shadow(color: .black.opacity(0.16), radius: 24, y: 12)
    }

    private var badgeCatalogID: Int? {
        guard case let .catalog(id) = confirmation.learned.recipeID else { return nil }
        return store.recipesById[id]?.id
    }

    private var sparkles: some View {
        ZStack {
            ForEach(0..<12, id: \.self) { index in
                let angle = Double(index) * .pi / 6
                Image(systemName: index.isMultiple(of: 3) ? "sparkle" : "circle.fill")
                    .font(.system(size: index.isMultiple(of: 3) ? 12 : 5))
                    .foregroundStyle(index.isMultiple(of: 2) ? Color.orange : Color.yellow)
                    .offset(x: cos(angle) * (sparklesExpanded ? 130 : 22),
                            y: sin(angle) * (sparklesExpanded ? 90 : 16) + 40)
                    .opacity(sparklesExpanded ? 0 : 0.9)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

#Preview("学会庆祝") {
    let records = CookedRecipeRecords()
    if let store = try? RecipeStore.loadBundled(inMemory: true),
       let confirmation = try? records.confirmLearned(.catalog(250), name: "番茄炒蛋") {
        CookingCelebrationView(confirmation: confirmation)
            .environment(records)
            .environment(store)
    }
}

enum CookingDates {
    static func plaque(_ date: Date) -> String { format(date, "yyyy.MM.dd") }
    static func celebration(_ date: Date) -> String { format(date, "yyyy年MM月dd日") }

    static func day(_ date: Date, now: Date = .now, calendar: Calendar = .current) -> String {
        if calendar.isDate(date, inSameDayAs: now) { return "今天" }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(date, inSameDayAs: yesterday) { return "昨天" }
        return format(date, calendar.component(.year, from: date) == calendar.component(.year, from: now) ? "M月d日" : "yyyy年M月d日")
    }

    private static func format(_ date: Date, _ pattern: String) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }
}
