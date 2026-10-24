import SwiftUI
import UIKit

/// 按入口固定表单用途，复用私密提交、联系方式和失败兜底。
struct AppFeedbackView: View {
    enum Purpose {
        case feedback, contribution

        var title: String { self == .feedback ? "意见反馈" : "菜谱投稿" }
        var submitTitle: String { self == .feedback ? "发送反馈" : "提交菜谱" }
    }

    let purpose: Purpose
    @Environment(FeedbackClient.self) private var feedback
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var category: FeedbackClient.Category = .bug
    @State private var note = ""
    @State private var contribution = RecipeContributionDraft()
    @State private var contact = ""
    @State private var sending = false
    @State private var showingSent = false
    @State private var showingFailed = false
    @State private var showingNoMail = false

    init(purpose: Purpose = .feedback) {
        self.purpose = purpose
    }

    private var submittedCategory: FeedbackClient.Category { purpose == .contribution ? .recipe : category }
    private var submittedNote: String { purpose == .contribution ? contribution.note : note }
    private var mailTopic: String { purpose == .contribution ? "菜谱投稿" : category.label }

    private var mailLines: [String] {
        [submittedNote.trimmingCharacters(in: .whitespacesAndNewlines), contact.isEmpty ? "" : "联系方式：\(contact)"]
    }

    private var mailText: String {
        FeedbackClient.mailText(topic: mailTopic, lines: mailLines)
    }

    private var canSend: Bool {
        guard !sending else { return false }
        if purpose == .contribution { return contribution.canSubmit(maxLength: FeedbackClient.maxNote) }
        return !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Form {
            if feedback.isTest {
                Section { Text("测试模式：反馈仅进入独立测试队列，不会发送到正式支持中心。") }
            }
            if purpose == .contribution {
                contributionFields
            } else {
                feedbackFields
            }
            Section {
                TextField("邮箱或微信（可选）", text: $contact)
                    .accessibilityIdentifier("feedback.contact")
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: contact) { _, value in
                        if value.count > FeedbackClient.maxContact {
                            contact = String(value.prefix(FeedbackClient.maxContact))
                        }
                    }
            } footer: {
                Text(purpose == .contribution
                     ? "投稿私密发送至云乐坊支持中心，由团队审核是否收录，不会自动公开。联系方式选填，便于补充菜谱信息；填写后这条投稿可能与你关联。"
                     : "反馈私密发送至云乐坊支持中心，仅团队可见，不会自动公开到需求墙。联系方式选填；填写后这条反馈可能与你关联，便于我们回复。")
            }
            Section {
                NavigationLink("隐私政策") { PrivacyPolicyView() }
            } footer: {
                if feedback.isTest {
                    Text("测试内容只进入独立测试集合，不会转发支持中心。")
                } else if feedback.isConfigured {
                    Text("提交会通过腾讯云 CloudBase 将填写的内容、联系方式及 App 平台和版本交给云乐坊支持中心，用于处理和回复。本次启动的随机标识经服务端散列后用于防止重复和限制请求次数。")
                } else {
                    Text("当前无法在 App 内提交，可前往支持中心。也可以打开邮件草稿发送至 \(FeedbackClient.supportEmail)，需要你在邮件应用中确认发送；邮件不会生成支持中心回执。")
                }
            }
            if !feedback.isConfigured {
                Section {
                    Link("去支持中心", destination: URL(string: "https://support.yunle.fun/contact?product=cook&source=cook-ios&intent=feedback")!)
                }
            }
        }
        .disabled(sending)
        .navigationTitle(purpose.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if sending {
                    ProgressView()
                } else {
                    Button(feedback.isConfigured ? purpose.submitTitle : "打开邮件草稿", action: send)
                        .accessibilityIdentifier("feedback.submit")
                        .disabled(!canSend)
                }
            }
        }
        .alert(feedback.isTest ? "测试反馈已收到" : (purpose == .contribution ? "投稿已收到，谢谢分享" : "收到了，谢谢反馈"), isPresented: $showingSent) {
            Button("好", role: .cancel) { dismiss() }
        }
        .alert("暂时没能提交", isPresented: $showingFailed) {
            Button("取消", role: .cancel) {}
            if !feedback.isTest {
                Button("去支持中心") { openURL(URL(string: "https://support.yunle.fun/contact?product=cook&source=cook-ios&intent=feedback")!) }
                Button("打开邮件草稿", action: sendMail)
                Button("复制邮箱和内容") { UIPasteboard.general.string = mailText }
            }
        } message: {
            Text(feedback.isTest ? "测试内容已保留，可以稍后重试。" : "内容已保留，可以稍后重试，或前往支持中心提交私密反馈。邮件作为可选方式，需要你确认发送。")
        }
        .alert("没有找到邮件 App", isPresented: $showingNoMail) {
            Button("好", role: .cancel) {}
        } message: {
            Text("邮箱 \(FeedbackClient.supportEmail) 和反馈内容已复制，可以用其他邮箱发给我们。原内容也保留在表单中。")
        }
        .sensoryFeedback(.success, trigger: showingSent) { _, sent in sent }
    }

    private var feedbackFields: some View {
        Group {
            Section {
                Picker("反馈类型", selection: $category) {
                    ForEach(FeedbackClient.Category.allCases) { item in
                        Text(item.label).tag(item)
                    }
                }
                .accessibilityIdentifier("feedback.category")
            } footer: {
                Text("反馈使用问题、提出功能建议，或告诉我们你想做哪道菜。")
            }
            Section {
                TextField(category.placeholder, text: $note, axis: .vertical)
                    .accessibilityIdentifier("feedback.note")
                    .lineLimit(5 ... 12)
                    .onChange(of: note) { _, value in
                        if value.count > FeedbackClient.maxNote {
                            note = String(value.prefix(FeedbackClient.maxNote))
                        }
                    }
            } header: {
                Text("具体说明")
            } footer: {
                Text("\(note.count)/\(FeedbackClient.maxNote)")
            }
        }
    }

    private var contributionFields: some View {
        Group {
            Section {
                TextField("例如：番茄炒蛋", text: $contribution.name)
                    .accessibilityLabel("菜名")
                    .accessibilityIdentifier("contribution.name")
            } header: {
                Text("菜名（必填）")
            } footer: {
                Text("分享你推荐的视频或图文菜谱。只想点菜？请到意见反馈选择「想要的菜没有」。")
            }
            Section {
                TextField("粘贴视频或图文链接", text: $contribution.source, axis: .vertical)
                    .accessibilityLabel("做法链接")
                    .accessibilityIdentifier("contribution.source")
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .lineLimit(1 ... 3)
            } header: {
                Text("做法链接（必填）")
            } footer: {
                if !contribution.source.isEmpty && !contribution.hasValidSource {
                    Text("请填写以 https:// 或 http:// 开头的完整网页链接。")
                        .foregroundStyle(.red)
                } else {
                    Text("支持 B 站、小红书等来源，请粘贴单个链接，方便核对做法和作者。")
                }
            }
            Section {
                TextField("推荐理由、食材或做法提示", text: $contribution.details, axis: .vertical)
                    .accessibilityIdentifier("contribution.details")
                    .lineLimit(3 ... 6)
            } header: {
                Text("补充说明（可选）")
            } footer: {
                VStack(alignment: .leading, spacing: 4) {
                    if contribution.note.count > FeedbackClient.maxNote {
                        Text("内容超出 \(contribution.note.count - FeedbackClient.maxNote) 字，请精简后提交。")
                            .foregroundStyle(.red)
                    } else {
                        Text("还可填写 \(FeedbackClient.maxNote - contribution.note.count) 字")
                    }
                }
            }
        }
    }

    private func send() {
        guard canSend else { return }
        guard feedback.isConfigured else {
            sendMail()
            return
        }
        sending = true
        Task {
            defer { sending = false }
            do {
                try await feedback.send(submittedCategory, note: submittedNote, contact: String(contact.prefix(FeedbackClient.maxContact)))
                showingSent = true
            } catch {
                showingFailed = true
            }
        }
    }

    private func sendMail() {
        openURL(FeedbackClient.mailURL(topic: mailTopic, lines: mailLines)) { accepted in
            if !accepted {
                UIPasteboard.general.string = mailText
                showingNoMail = true
            }
        }
    }
}
