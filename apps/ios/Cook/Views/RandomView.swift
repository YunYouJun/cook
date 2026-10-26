import CookCore
import SwiftUI
import UIKit

/// 今天吃什么：随机一道菜。选了食材时从能做的菜里挑，否则从全部菜里挑；忌口、不吃辣和筛选都生效。
/// 点按钮或摇一摇手机都能换一道
struct RandomView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var recipe: Recipe?
    @State private var showingDietaryGoals = false
    @State private var rolls = 0
    /// 换菜时快速闪过的几道菜，像摇骰子；nil 时显示选中的菜
    @State private var shuffling: String?
    @State private var shuffleTask: Task<Void, Never>?
    @State private var isVisible = false
    /// 换好一道菜时加一，用来触发落定的触感
    @State private var landings = 0

    /// 候选菜。和 Web 的 randomPool 一致，只是不看结果页的菜名搜索
    private var pool: [Recipe] {
        session.candidates(in: store, ignoringKeyword: true)
    }

    private func scopeText(count: Int) -> String {
        if let goal = session.dietaryGoal {
            return "按“\(goal.title)”和当前条件，从 \(count) 道已核对做法里挑"
        }
        if !session.selectedStuff.isEmpty {
            return "从已选食材能做的 \(count) 道菜里挑"
        }
        if session.tool != nil || !session.avoid.isEmpty || session.activeFilterCount > 0 {
            return "按你的厨具、忌口和筛选，从 \(count) 道菜里挑"
        }
        return "从全部 \(count) 道菜里挑"
    }

    var body: some View {
        let pool = pool

        NavigationStack {
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 20) {
                        GroupBox { DietaryGoalPickerButton(isPresented: $showingDietaryGoals) }
                        Spacer(minLength: 0)
                        if let recipe {
                            NavigationLink(value: RecipeRoute(recipe: recipe, dietaryGoal: session.dietaryGoal)) {
                                card(recipe)
                            }
                            .buttonStyle(.plain)
                            .disabled(shuffling != nil)
                            .accessibilityHint("打开菜谱详情")
                            .accessibilityIdentifier("random.openRecipe")
                            .id(recipe.id)
                            .transition(reduceMotion ? .opacity : .asymmetric(
                                insertion: .scale(scale: 0.9).combined(with: .opacity),
                                removal: .opacity
                            ))
                        } else {
                            ContentUnavailableView(
                                "没有符合条件的菜",
                                systemImage: "dice",
                                description: Text(store.dietaryUnavailableMessage(for: session.dietaryGoal) ?? "换几样食材，或者放宽忌口和筛选。")
                            )
                        }
                        Spacer(minLength: 0)
                        VStack(spacing: 10) {
                            Text(scopeText(count: pool.count))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .contentTransition(.numericText())
                            Button(action: roll) {
                                Label {
                                    Text("换一道")
                                } icon: {
                                    Image(systemName: "dice")
                                        .symbolEffect(.bounce, value: rolls)
                                }
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                            }
                            .prominentButtonStyle()
                            .controlSize(.large)
                            .disabled(pool.isEmpty)
                            .accessibilityHint("也可以摇一摇手机")
                            .accessibilityIdentifier("random.roll")
                        }
                    }
                    .padding()
                    .frame(maxWidth: 600)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: geometry.size.height)
                }
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("今天吃什么")
            .recipeDestinations()
            .onAppear {
                isVisible = true
                // 条件变了（比如回做菜页换了食材），当前这道不在候选里了就重新挑
                if recipe.map({ current in !pool.contains { $0.id == current.id } }) ?? true {
                    recipe = store.randomRecipe(from: pool)
                }
            }
            .onDisappear {
                isVisible = false
                shuffleTask?.cancel()
                shuffling = nil
            }
            .onChange(of: pool.map(\.id)) { _, ids in
                shuffleTask?.cancel()
                shuffling = nil
                if recipe.map({ !ids.contains($0.id) }) ?? true {
                    recipe = store.randomRecipe(from: pool)
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
                if isVisible, !pool.isEmpty {
                    roll()
                }
            }
            // 闪过每一道菜时轻轻「咔哒」一下，停下来时重一点
            .sensoryFeedback(.selection, trigger: shuffling) { _, new in new != nil }
            .sensoryFeedback(.impact(weight: .medium), trigger: landings)
        }
        .sheet(isPresented: $showingDietaryGoals) { DietaryGoalSelectionSheet() }
    }

    private func card(_ recipe: Recipe) -> some View {
        VStack(spacing: 12) {
            Text(shuffling ?? store.thumbnail(for: recipe))
                .font(.system(size: 96))
                .keyframeAnimator(initialValue: Toss(), trigger: rolls) { view, toss in
                    view
                        .rotationEffect(.degrees(toss.angle))
                        .scaleEffect(toss.scale)
                        .offset(y: toss.lift)
                } keyframes: { _ in
                    KeyframeTrack(\.angle) {
                        CubicKeyframe(-14, duration: 0.12)
                        CubicKeyframe(12, duration: 0.14)
                        CubicKeyframe(-6, duration: 0.14)
                        SpringKeyframe(0, duration: 0.3, spring: .bouncy)
                    }
                    KeyframeTrack(\.scale) {
                        CubicKeyframe(1.12, duration: 0.2)
                        SpringKeyframe(1, duration: 0.4, spring: .bouncy)
                    }
                    KeyframeTrack(\.lift) {
                        CubicKeyframe(-18, duration: 0.18)
                        SpringKeyframe(0, duration: 0.42, spring: .bouncy)
                    }
                }
                .accessibilityHidden(true)
            Text(recipe.name)
                .font(.title.bold())
                .multilineTextAlignment(.center)
                .redacted(reason: shuffling == nil ? [] : .placeholder)
            Text(store.meta(for: recipe))
                .foregroundStyle(.secondary)
                .redacted(reason: shuffling == nil ? [] : .placeholder)
            if let goal = session.dietaryGoal, let entry = store.dietaryCatalog?.entry(for: recipe, goal: goal) {
                Text(entry.reason(for: goal))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            // 卡片本身能点，用一行带箭头的提示说明这一点
            Label("看做法", systemImage: "chevron.right")
                .labelStyle(TrailingIconLabelStyle())
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.tint)
                .padding(.top, 4)
                .opacity(shuffling == nil ? 1 : 0)
        }
        .padding(32)
        .frame(maxWidth: .infinity)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .shadow(color: .black.opacity(0.08), radius: 16, y: 6)
    }

    private func roll() {
        let pool = pool
        let next = store.randomRecipe(from: pool, excluding: recipe?.id)
        shuffleTask?.cancel()
        if reduceMotion {
            withAnimation(.easeInOut(duration: 0.2)) { recipe = next }
            landings += 1
            return
        }
        rolls += 1
        // 先快后慢地闪过几道菜，再落到选中的那道
        let faces = pool.shuffled().prefix(6).map(store.thumbnail)
        shuffleTask = Task { @MainActor in
            for (index, face) in faces.enumerated() {
                shuffling = face
                try? await Task.sleep(for: .milliseconds(45 + index * 20))
                if Task.isCancelled {
                    return
                }
            }
            shuffling = nil
            withAnimation(.bouncy(duration: 0.4)) { recipe = next }
            landings += 1
        }
    }
}

/// 换菜时 emoji 的「抛骰子」动作
private struct Toss {
    var angle: Double = 0
    var scale: Double = 1
    var lift: Double = 0
}

/// 文字在前、图标在后，比如“看做法 >”
private struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.title
            configuration.icon
        }
    }
}

extension Notification.Name {
    /// 摇一摇手机
    static let deviceDidShake = Notification.Name("fun.yunle.cook.deviceDidShake")
}

extension UIWindow {
    // SwiftUI 没有摇一摇的回调，从窗口的响应链转出来
    override open func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        super.motionEnded(motion, with: event)
        if motion == .motionShake {
            NotificationCenter.default.post(name: .deviceDidShake, object: nil)
        }
    }
}
