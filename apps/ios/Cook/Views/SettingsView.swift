import SwiftUI

/// 我的：做菜偏好、反馈和关于
struct SettingsView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(FeedbackClient.self) private var feedback
    @AppStorage(FeedbackClient.signalsKey) private var shareSignals = true

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "-"
        let build = info?["CFBundleVersion"] as? String ?? "-"
        return "\(version)（\(build)）"
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    PreferenceRows()
                } header: {
                    Text("做菜偏好")
                } footer: {
                    Text("登录 iCloud 后，收藏、浏览记录和做菜偏好会在你的设备间同步。")
                }
                Section {
                    if feedback.isConfigured {
                        NavigationLink("意见反馈") {
                            AppFeedbackView()
                        }
                    }
                    Link("在 GitHub 上反馈", destination: URL(string: "https://github.com/YunYouJun/cook/issues")!)
                    // 没配置反馈地址的构建不会发筛选信号，开关也就不显示
                    if feedback.isConfigured {
                        Toggle("帮助改进推荐", isOn: $shareSignals)
                    }
                } header: {
                    Text("反馈")
                } footer: {
                    if feedback.isConfigured {
                        Text("匿名发送选过的食材组合和点开了哪道菜，用来补菜和调整排序，不含账号和设备信息。")
                    }
                }
                Section("关于") {
                    LabeledContent("版本", value: appVersion)
                    LabeledContent("菜谱", value: "\(store.recipes.count) 道")
                    LabeledContent("数据版本", value: store.data.meta.dataVersion)
                    Link("网页版：食用手册", destination: URL(string: "https://cook.yunyoujun.cn")!)
                }
            }
            .navigationTitle("我的")
        }
    }
}

/// 意见反馈：不针对某道菜，不用登录，可以留联系方式
struct AppFeedbackView: View {
    @Environment(FeedbackClient.self) private var feedback
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var category: FeedbackClient.Category = .bug
    @State private var note = ""
    @State private var contact = ""
    @State private var sending = false
    @State private var showingSent = false
    @State private var showingFailed = false

    private var canSend: Bool {
        !sending && !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        Form {
            Picker("类型", selection: $category) {
                ForEach(FeedbackClient.Category.allCases) { item in
                    Text(item.label).tag(item)
                }
            }
            Section {
                TextField("说说具体情况", text: $note, axis: .vertical)
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
            } footer: {
                Text("留下联系方式的话，我们处理后可以告诉你。")
            }
        }
        .navigationTitle("意见反馈")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                if sending {
                    ProgressView()
                } else {
                    Button("发送", action: send)
                        .disabled(!canSend)
                }
            }
        }
        .alert("收到了，谢谢反馈", isPresented: $showingSent) {
            Button("好", role: .cancel) { dismiss() }
        }
        .alert("暂时没能提交", isPresented: $showingFailed) {
            Button("取消", role: .cancel) {}
            Button("去 GitHub") { openURL(URL(string: "https://github.com/YunYouJun/cook/issues/new")!) }
        } message: {
            Text("可以稍后再试，或者到 GitHub 提一个 issue。")
        }
        .sensoryFeedback(.success, trigger: showingSent) { _, sent in sent }
    }

    private func send() {
        sending = true
        Task {
            defer { sending = false }
            do {
                try await feedback.send(category, note: note, contact: String(contact.prefix(FeedbackClient.maxContact)))
                showingSent = true
            } catch {
                showingFailed = true
            }
        }
    }
}
