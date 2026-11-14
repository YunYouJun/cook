import CookCore
import SwiftUI

/// Local guidance surface attached below the current step and outside its completion button.
struct RecipeStepGuidancePanel: View {
    @Environment(CookAISettings.self) private var ai
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let step: RecipePresentation.Step
    let guidance: KitchenStepGuidanceModel
    let explain: () -> Void
    let ask: (String) -> Void
    let retry: (StepGuidanceKind, String?) -> Void
    @State private var asking = false
    @State private var question = ""
    @State private var lastKind: StepGuidanceKind = .explain
    @State private var lastQuestion: String?
    @State private var panBounce = false
    @FocusState private var questionFocused: Bool

    private var response: StepGuidanceResponse? {
        guard guidance.applies(to: step.id) else { return nil }
        return guidance.response
    }

    private var citedSources: [StepGuidanceSource] {
        guard let response, let prompt = guidance.prompt else { return [] }
        return response.sourceIDs.compactMap { id in
            prompt.sources.first { $0.id == id }
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            guidanceActions

            if asking {
                VStack(alignment: .leading, spacing: 8) {
                    TextField("只问当前原文步骤，例如：怎样判断油温？", text: $question, axis: .vertical)
                        .lineLimit(2 ... 4)
                        .focused($questionFocused)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .privacySensitive()
                        .padding(12)
                        .background(Color(uiColor: .tertiarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel("询问当前步骤")
                        .accessibilityIdentifier("recipe.guidance.questionInput")
                    HStack {
                        Text("\(question.count) / \(StepGuidanceRequest.maximumQuestionLength)")
                            .font(.caption)
                            .foregroundStyle(question.count > StepGuidanceRequest.maximumQuestionLength ? .red : .secondary)
                            .monospacedDigit()
                        Spacer()
                        Button("发送问题", systemImage: "arrow.up.circle.fill") {
                            sendQuestion()
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(!canSend)
                        .accessibilityIdentifier("recipe.guidance.ask")
                    }
                }
            }

            if guidance.isLoading {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("正在核对当前步骤原文…")
                        .font(.subheadline)
                    Spacer()
                    Button("取消") { guidance.cancel() }
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("recipe.guidance.cancel")
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("recipe.guidance.loading")
            } else if guidance.phase == .error, let message = guidance.message {
                VStack(alignment: .leading, spacing: 10) {
                    Label(message, systemImage: "exclamationmark.triangle")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("recipe.guidance.message")
                    HStack {
                        Button {
                            retry(lastKind, lastQuestion)
                        } label: {
                            Text("重试")
                                .frame(minWidth: 44, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("recipe.guidance.retry")
                        if !ai.canUseRemote {
                            NavigationLink("AI 设置") { AISettingsView() }
                                .frame(minHeight: 44)
                                .accessibilityIdentifier("recipe.guidance.settings")
                        }
                    }
                }
            } else if let response {
                answer(response)
            } else if !ai.canUseRemote {
                HStack(spacing: 8) {
                    Text(ai.provider == .managedDeepSeek ? ai.managedStatus.message : "选择个人 Key 或会员内置服务后，才可按需讲解。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Spacer()
                    NavigationLink("AI 设置") { AISettingsView() }
                        .font(.footnote)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("recipe.guidance.settings")
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            if showsDetails {
                RoundedRectangle(cornerRadius: 16)
                    .fill(Color(uiColor: .secondarySystemGroupedBackground))
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("recipe.guidance.panel")
        .toolbar {
            if questionFocused, asking {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成") { questionFocused = false }
                        .accessibilityIdentifier("recipe.guidance.finishEditing")
                }
            }
        }
        .onChange(of: guidance.phase) {
            if guidance.phase == .loading { questionFocused = false }
        }
    }

    private func answer(_ response: StepGuidanceResponse) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(response.status == .sourceDoesNotSay ? "原方未说明" : "本步讲解",
                  systemImage: response.status == .sourceDoesNotSay ? "questionmark.circle" : "text.bubble")
                .font(.subheadline.weight(.semibold))
            Text(recipeMarkdown(response.answer))
                .font(.body)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("recipe.guidance.answer")
            if !citedSources.isEmpty {
                DisclosureGroup("核对原文（\(citedSources.count) 处）") {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(citedSources) { source in
                            Text(recipeMarkdown(source.text))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(.top, 8)
                }
                .font(.footnote.weight(.medium))
                .accessibilityIdentifier("recipe.guidance.sources")
            }
            Text("引用用于核对原文位置，不保证模型解释完全正确；用量、时间、温度和进度仍以原文及工作台为准。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .contain)
    }

    private var canSend: Bool {
        let trimmed = question.trimmingCharacters(in: .whitespacesAndNewlines)
        return !trimmed.isEmpty && trimmed.count <= StepGuidanceRequest.maximumQuestionLength && !guidance.isLoading
    }

    private var showsDetails: Bool {
        asking || guidance.isLoading || guidance.phase == .error || response != nil || !ai.canUseRemote
    }

    @ViewBuilder
    private var guidanceActions: some View {
        if dynamicTypeSize.isAccessibilitySize {
            stackedGuidanceActions
        } else {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) {
                    guidanceActionButtons
                }
                stackedGuidanceActions
            }
        }
    }

    private var stackedGuidanceActions: some View {
        VStack(alignment: .leading, spacing: 8) {
            guidanceActionButtons
        }
    }

    @ViewBuilder
    private var guidanceActionButtons: some View {
        guidanceButton("本步讲解", identifier: "recipe.guidance.explain") {
            Image(systemName: "frying.pan")
                .foregroundStyle(.orange)
                .symbolEffect(.bounce, options: .nonRepeating, value: panBounce)
        } action: {
            if !reduceMotion { panBounce.toggle() }
            lastKind = .explain
            lastQuestion = nil
            explain()
        }
        .buttonStyle(.bordered)
        guidanceButton(asking ? "收起提问" : "问这一步", identifier: "recipe.guidance.question") {
            Image(systemName: asking ? "chevron.up" : "questionmark.bubble")
        } action: {
            withAnimation(.easeInOut(duration: 0.2)) {
                asking.toggle()
            }
            if !asking { questionFocused = false }
        }
        .buttonStyle(.borderless)
    }

    private func sendQuestion() {
        let trimmed = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= StepGuidanceRequest.maximumQuestionLength else { return }
        lastKind = .question
        lastQuestion = trimmed
        questionFocused = false
        ask(trimmed)
    }

    private func guidanceButton<Icon: View>(_ title: String, identifier: String, @ViewBuilder icon: () -> Icon,
                                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label { Text(title) } icon: { icon().accessibilityHidden(true) }
                .font(.subheadline.weight(.medium))
                .fixedSize(horizontal: false, vertical: true)
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .disabled(guidance.isLoading)
        .accessibilityIdentifier(identifier)
    }
}
