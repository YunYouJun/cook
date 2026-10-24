import CookCore
import SwiftUI

/// The selected recipe stays attached while editing, retrying, or opening a mail draft.
struct RecipeFeedbackView: View {
    @Environment(FeedbackClient.self) private var feedback
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    let recipe: Recipe
    @State private var issue: FeedbackClient.RecipeIssue
    @State private var linkIssue: FeedbackClient.LinkIssue = .unspecified
    @State private var note = ""
    @State private var pending = false
    @State private var sent = false
    @State private var errorMessage = ""

    init(recipe: Recipe) {
        self.recipe = recipe
        _issue = State(initialValue: FeedbackClient.RecipeIssue.available(for: recipe).first ?? .other)
    }

    private var isLink: Bool { issue == .video || issue == .text }
    private var mail: URL {
        FeedbackClient.mailURL(topic: recipe.name, lines: [
            "菜谱：\(recipe.name)（#\(recipe.id)）", "问题：\(issue.label)",
            isLink && linkIssue != .unspecified ? "具体表现：\(linkIssue.label)" : "", note,
            recipe.videoURL?.absoluteString ?? "", recipe.link ?? "",
        ])
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(recipe.name).font(.headline)
                    Text("菜谱 #\(recipe.id) · 已自动携带菜名、编号和做法链接")
                        .font(.footnote).foregroundStyle(.secondary)
                    if let video = recipe.videoURL { Link("视频做法", destination: video) }
                    if let text = recipe.link.flatMap(URL.init(string:)) { Link("图文做法", destination: text) }
                }
                if feedback.isTest {
                    Section { Text("测试模式：反馈只进入测试队列，不会进入正式审核。") }
                }
                Section("问题详情") {
                    Picker("问题类型", selection: $issue) {
                        ForEach(FeedbackClient.RecipeIssue.available(for: recipe)) { item in
                            Text(item.label).tag(item)
                        }
                    }
                    .accessibilityIdentifier("recipe-feedback.kind")
                    if isLink {
                        Picker("具体表现（选填）", selection: $linkIssue) {
                            ForEach(FeedbackClient.LinkIssue.allCases) { item in Text(item.label).tag(item) }
                        }
                    }
                    TextField(issue == .other ? "请描述具体问题" : "补充说明（选填）", text: $note, axis: .vertical)
                        .lineLimit(4...8)
                        .accessibilityIdentifier("recipe-feedback.note")
                        .onChange(of: note) { _, value in
                            if value.count > 200 { note = String(value.prefix(200)) }
                        }
                    Text("\(note.count) / 200").font(.caption).foregroundStyle(.secondary)
                }
                .disabled(pending)
                Section {
                    Text("无需登录。提交会附带平台、应用构建与菜谱版本，方便团队排查。请勿填写密码等敏感信息。")
                        .font(.footnote).foregroundStyle(.secondary)
                    if !errorMessage.isEmpty {
                        Text(errorMessage).foregroundStyle(.red).accessibilityIdentifier("recipe-feedback.error")
                        if !feedback.isTest { Button("打开带菜谱信息的邮件草稿") { openURL(mail) } }
                    }
                    Button(pending ? "正在提交…" : feedback.isTest ? "提交测试反馈" : "提交反馈", action: submit)
                        .disabled(pending || (issue == .other && note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
                        .accessibilityIdentifier("recipe-feedback.submit")
                }
            }
            .navigationTitle("反馈这道菜")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(pending) }
            }
            .interactiveDismissDisabled(pending)
            .alert(feedback.isTest ? "测试反馈已收到" : "收到了，谢谢反馈", isPresented: $sent) {
                Button("完成") { dismiss() }
            } message: {
                Text(feedback.isTest ? "这是一条测试记录。" : "团队会结合这道菜的做法链接核查。")
            }
        }
    }

    private func submit() {
        guard !pending else { return }
        pending = true
        errorMessage = ""
        Task {
            defer { pending = false }
            do {
                try await feedback.report(recipe, issue: issue, note: note, linkIssue: linkIssue)
                sent = true
            } catch {
                errorMessage = "暂时没能提交，内容已保留，请重试。"
            }
        }
    }
}
