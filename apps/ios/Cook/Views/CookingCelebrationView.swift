import CookCore
import SwiftUI
import UIKit

struct CookingCelebrationView: View {
    @Environment(CookedRecipeRecords.self) private var records
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
                VStack(spacing: 22) {
                    Image(systemName: "sparkles")
                        .font(.system(size: 40))
                        .foregroundStyle(.orange)
                        .accessibilityHidden(true)
                    Text("今天学会了 · \(confirmation.learned.recipeName ?? "这道菜")")
                        .font(.title2.weight(.semibold))
                        .multilineTextAlignment(.center)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityFocused($headingFocused)
                    Text(CookingDates.celebration(confirmation.learned.learnedAt))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Text("第 \(confirmation.learnedCount) 道拿手菜")
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text("把这一刻，留在你的做菜足迹里。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                    Button("太棒了") { dismiss() }
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
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("cooking.celebration.undo")
                }
                .padding(28)
                .frame(maxWidth: 400)
                .background {
                    RoundedRectangle(cornerRadius: 28)
                        .fill(.background)
                        .overlay {
                            RoundedRectangle(cornerRadius: 28)
                                .fill(.orange.opacity(0.06))
                        }
                }
                .contentShape(RoundedRectangle(cornerRadius: 28))
                .onTapGesture {}
                .overlay(alignment: .top) {
                    if !reduceMotion {
                        sparkles
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
                }
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
    if let confirmation = try? records.confirmLearned(.catalog(42), name: "番茄炒蛋") {
        CookingCelebrationView(confirmation: confirmation)
            .environment(records)
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
