import SwiftUI
import UIKit

/// 意见反馈：不针对某道菜，不用登录，可以留联系方式
struct AppFeedbackView: View {
    @Environment(FeedbackClient.self) private var feedback
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var category: FeedbackClient.Category
    @State private var note = ""
    @State private var contact = ""
    @State private var sending = false
    @State private var showingSent = false
    @State private var showingFailed = false
    @State private var showingNoMail = false
    @State private var receipt: FeedbackClient.Receipt?
    @State private var submissionId: String?
    @State private var submittedBody = ""

    init(category: FeedbackClient.Category = .bug) {
        _category = State(initialValue: category)
    }

    private var mailLines: [String] {
        [note.trimmingCharacters(in: .whitespacesAndNewlines), contact.isEmpty ? "" : "联系方式：\(contact)"]
    }

    private var mailText: String {
        FeedbackClient.mailText(topic: category.label, lines: mailLines)
    }

    private var canSend: Bool {
        !sending && receipt == nil && !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Form {
            if let receipt {
                Section("反馈已收到") {
                    Text("反馈编号：\(receipt.id)")
                    if let url = receipt.ticketURL {
                        Link("查看私密工单与回复", destination: url)
                        ShareLink("保存私密工单链接", item: url)
                        Text("请保存链接，不要转发。可在工单页补充信息和确认已读。")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            Picker("类型", selection: $category) {
                ForEach(FeedbackClient.Category.allCases) { item in
                    Text(item.label).tag(item)
                }
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
            } footer: {
                Text("\(note.count)/\(FeedbackClient.maxNote)")
            }
            Section {
                TextField("邮箱或微信（可选）", text: $contact)
                    .textContentType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: contact) { _, value in
                        if value.count > FeedbackClient.maxContact {
                            contact = String(value.prefix(FeedbackClient.maxContact))
                        }
                    }
            } footer: {
                Text("反馈私密发送至云乐坊支持中心，仅团队可见，不会自动公开到需求墙。联系方式选填；填写后这条反馈可能与你关联，便于我们回复。")
            }
            Section {
                NavigationLink("隐私政策") { PrivacyPolicyView() }
            } footer: {
                if feedback.isConfigured {
                    Text("点按发送会通过腾讯云 CloudBase 将反馈内容、你填写的联系方式及 App 平台和版本交给云乐坊支持中心，用于处理问题和回复。本次启动的随机标识经服务端散列后用于防止重复和限制请求次数。")
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
        .navigationTitle(category == .recipe ? "菜谱投稿" : "意见反馈")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if sending {
                    ProgressView()
                } else {
                    Button(feedback.isConfigured ? "发送" : "打开邮件草稿", action: send)
                        .disabled(!canSend)
                }
            }
        }
        .alert("收到了，谢谢反馈", isPresented: $showingSent) {
            Button("好", role: .cancel) { if receipt == nil { dismiss() } }
        }
        .alert("暂时没能提交", isPresented: $showingFailed) {
            Button("取消", role: .cancel) {}
            Button("去支持中心") { openURL(URL(string: "https://support.yunle.fun/contact?product=cook&source=cook-ios&intent=feedback")!) }
            Button("打开邮件草稿", action: sendMail)
            Button("复制邮箱和内容") { UIPasteboard.general.string = mailText }
        } message: {
            Text("内容已保留，可以稍后重试，或前往支持中心提交私密反馈。邮件作为可选方式，需要你确认发送。")
        }
        .alert("没有找到邮件 App", isPresented: $showingNoMail) {
            Button("好", role: .cancel) {}
        } message: {
            Text("邮箱 \(FeedbackClient.supportEmail) 和反馈内容已复制，可以用其他邮箱发给我们。原内容也保留在表单中。")
        }
        .sensoryFeedback(.success, trigger: showingSent) { _, sent in sent }
    }

    private func send() {
        guard canSend else { return }
        guard feedback.isConfigured else {
            sendMail()
            return
        }
        sending = true
        let bodyKey = [category.rawValue, note, contact].joined(separator: "\u{0}")
        if submittedBody != bodyKey || submissionId == nil {
            submittedBody = bodyKey
            submissionId = UUID().uuidString
        }
        Task {
            defer { sending = false }
            do {
                receipt = try await feedback.send(category, note: note, contact: String(contact.prefix(FeedbackClient.maxContact)), submissionId: submissionId)
                showingSent = true
            } catch {
                showingFailed = true
            }
        }
    }

    private func sendMail() {
        openURL(FeedbackClient.mailURL(topic: category.label, lines: mailLines)) { accepted in
            if !accepted {
                UIPasteboard.general.string = mailText
                showingNoMail = true
            }
        }
    }
}
