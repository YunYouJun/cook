import CookCore
import SwiftUI

struct KitchenTimerSection: View {
    @Environment(KitchenWorkbench.self) private var workbench
    @State private var durationSheet: DurationSelection?
    let recipe: Recipe

    private struct DurationSelection: Identifiable {
        let id = UUID()
        let minutes: Int
    }

    var body: some View {
        Section("计时器") {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let timer = workbench.state(for: recipe.id).timer
                let phase = timer.phase(at: context.date)
                VStack(spacing: 12) {
                    Text(timer.display(at: context.date))
                        .font(.system(.largeTitle, design: .rounded).monospacedDigit())
                        .accessibilityIdentifier("kitchen.countdown")
                    Text(status(phase))
                        .font(.subheadline)
                        .foregroundStyle(phase == .finished ? Color.accentColor : Color.secondary)
                        .accessibilityIdentifier("kitchen.timerStatus")

                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 16) { controls(phase) }
                        VStack(spacing: 12) { controls(phase) }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .labelStyle(.titleAndIcon)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .sensoryFeedback(.success, trigger: phase == .finished)
            }

            let timer = workbench.state(for: recipe.id).timer
            if !timer.hasStarted {
                Button {
                    durationSheet = DurationSelection(minutes: max(1, Int(timer.duration / 60)))
                } label: {
                    LabeledContent("时长", value: "\(Int(timer.duration / 60)) 分钟")
                }
                .accessibilityIdentifier("kitchen.duration")
            }
            if workbench.reminderUnavailable.contains(recipe.id) {
                Text("未能开启到时通知，返回工作台可查看剩余时间。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .sheet(item: $durationSheet) { selection in
            KitchenDurationSheet(minutes: selection.minutes) { minutes in
                update { $0.configure(seconds: Double(minutes * 60)) }
            }
        }
    }

    @ViewBuilder
    private func controls(_ phase: KitchenTimer.Phase) -> some View {
        switch phase {
        case .ready:
            Button("开始计时", systemImage: "play.fill") { update { $0.start(at: .now) } }
                .buttonStyle(.borderedProminent)
        case .running:
            Button("暂停", systemImage: "pause.fill") { update { $0.pause(at: .now) } }
            Button("重置", systemImage: "arrow.counterclockwise") { update { $0.reset() } }
        case .paused:
            Button("继续", systemImage: "play.fill") { update { $0.resume(at: .now) } }
                .buttonStyle(.borderedProminent)
            Button("重置", systemImage: "arrow.counterclockwise") { update { $0.reset() } }
        case .finished:
            Button("再计一次", systemImage: "arrow.clockwise") { update { $0.start(at: .now) } }
                .buttonStyle(.borderedProminent)
            Button("重置", systemImage: "arrow.counterclockwise") { update { $0.reset() } }
        }
    }

    private func update(_ change: (inout KitchenTimer) -> Void) {
        workbench.updateTimer(recipeID: recipe.id, recipeName: recipe.name, change)
    }

    private func status(_ phase: KitchenTimer.Phase) -> String {
        switch phase {
        case .ready: return "准备好后开始"
        case .running: return "计时中"
        case .paused: return "已暂停"
        case .finished: return "计时结束"
        }
    }
}

private struct KitchenDurationSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State var minutes: Int
    let save: (Int) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Picker("时长", selection: $minutes) {
                    ForEach(1 ... 180, id: \.self) { value in
                        Text("\(value) 分钟").tag(value)
                    }
                }
                .pickerStyle(.wheel)
                .labelsHidden()
                .accessibilityLabel("计时分钟数")
            }
            .navigationTitle("计时时长")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") {
                        save(minutes)
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}
