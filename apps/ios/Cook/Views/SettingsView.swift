import CoreTransferable
import SwiftData
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 我的：做菜偏好、和网页版互通数据、反馈和关于
struct SettingsView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(FeedbackClient.self) private var feedback
    @Environment(\.modelContext) private var context
    @AppStorage(FeedbackClient.signalsKey) private var shareSignals = true
    // 导出要带上软删除的记录，网页版合并时才知道哪些被取消了
    @Query private var allFavorites: [Favorite]
    @Query private var allVisits: [HistoryEntry]
    @State private var importingFile = false
    @State private var importResult: ImportResult?

    private enum ImportResult: Identifiable {
        case done(favorites: Int, visits: Int)
        case failed

        var id: String {
            switch self {
            case let .done(favorites, visits): return "done-\(favorites)-\(visits)"
            case .failed: return "failed"
            }
        }
    }

    private var importTitle: String {
        switch importResult {
        case .failed?: return "导入失败"
        default: return "导入完成"
        }
    }

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
                    ShareLink(
                        item: WebExportFile(text: WebUserData.export(favorites: allFavorites, visits: allVisits, recipesById: store.recipesById)),
                        preview: SharePreview("云食册的收藏和浏览记录")
                    ) {
                        Label("导出给网页版", systemImage: "square.and.arrow.up")
                    }
                    Button {
                        importingFile = true
                    } label: {
                        Label("从文件导入", systemImage: "folder")
                    }
                    LabeledContent {
                        PasteButton(payloadType: String.self) { strings in
                            Task { @MainActor in importText(strings.joined()) }
                        }
                        .labelStyle(.iconOnly)
                        .buttonBorderShape(.capsule)
                    } label: {
                        Label("粘贴网页版复制的数据", systemImage: "doc.on.clipboard")
                    }
                } header: {
                    Text("和网页版互通")
                } footer: {
                    Text("网页版在「我的 → 设置 → 收藏和历史」里导出或复制，再到这里导入。导入只会添加，不会覆盖 App 里已有的收藏和浏览记录。")
                }
                Section {
                    // 没配置反馈地址的构建也保留入口，提交时改成发邮件
                    NavigationLink("意见反馈") {
                        AppFeedbackView()
                    }
                    NavigationLink("菜谱投稿") {
                        AppFeedbackView(category: .recipe)
                    }
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
                    Link("更新日志", destination: URL(string: "https://docs.yunyoujun.cn/projects/cook/changelog")!)
                    Link("网页版：食用手册", destination: URL(string: "https://cook.yunyoujun.cn")!)
                }
                Section {
                    Link("在 GitHub 上提 Issue", destination: URL(string: "https://github.com/YunYouJun/cook/issues")!)
                    Link("参与讨论", destination: URL(string: "https://github.com/YunYouJun/cook/discussions")!)
                } header: {
                    Text("开发者")
                } footer: {
                    Text("能复现的问题和代码相关的建议可以在 GitHub 上提；其他反馈用上面的「意见反馈」就好。")
                }
            }
            .navigationTitle("我的")
            .fileImporter(isPresented: $importingFile, allowedContentTypes: [.json, .plainText]) { result in
                guard case let .success(url) = result else {
                    return
                }
                let accessing = url.startAccessingSecurityScopedResource()
                defer {
                    if accessing {
                        url.stopAccessingSecurityScopedResource()
                    }
                }
                importText((try? String(contentsOf: url, encoding: .utf8)) ?? "")
            }
            .alert(
                importTitle,
                isPresented: Binding(get: { importResult != nil }, set: { if !$0 { importResult = nil } }),
                presenting: importResult
            ) { _ in
                Button("好", role: .cancel) {}
            } message: { result in
                switch result {
                case let .done(favorites, visits):
                    Text(favorites + visits == 0 ? "没有新的内容，App 里已经都有了。" : "新加了 \(favorites) 个收藏、\(visits) 条浏览记录。")
                case .failed:
                    Text("没能识别这份数据，请确认它来自食用手册网页版的导出。")
                }
            }
            .sensoryFeedback(trigger: importResult?.id) { _, id in
                guard let id else {
                    return nil
                }
                return id == "failed" ? .error : .success
            }
        }
    }

    private func importText(_ text: String) {
        guard let data = WebUserData.parse(text) else {
            importResult = .failed
            return
        }
        let added = context.importWebData(data, recipeIdByName: store.recipeIdByName, validIds: Set(store.recipesById.keys))
        importResult = .done(favorites: added.favorites, visits: added.visits)
    }
}

/// 导出给网页版的数据：分享成 JSON 文件（网页版「从文件导入」），也能当文本复制（网页版「粘贴导入」）
struct WebExportFile: Transferable {
    let text: String

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .json) { file in
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyyMMdd-HHmm"
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("cook-data-\(formatter.string(from: Date())).json")
            try Data(file.text.utf8).write(to: url, options: .atomic)
            return SentTransferredFile(url)
        }
        ProxyRepresentation(exporting: \.text)
    }
}

/// 意见反馈：不针对某道菜，不用登录，可以留联系方式。没配置反馈地址或提交失败时改成发邮件
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

    init(category: FeedbackClient.Category = .bug) {
        _category = State(initialValue: category)
    }

    private var canSend: Bool {
        !sending && !trimmedNote.isEmpty
    }

    private var trimmedNote: String {
        note.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var trimmedContact: String {
        String(contact.trimmingCharacters(in: .whitespacesAndNewlines).prefix(FeedbackClient.maxContact))
    }

    private var mailURL: URL {
        FeedbackClient.mailURL(
            topic: category.label,
            lines: [trimmedNote, trimmedContact.isEmpty ? "" : "联系方式：\(trimmedContact)"]
        )
    }

    var body: some View {
        Form {
            Picker("类型", selection: $category) {
                ForEach(FeedbackClient.Category.allCases) { item in
                    Text(item.label).tag(item)
                }
            }
            Section {
                TextField(category.placeholder, text: $note, axis: .vertical)
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
                if feedback.isConfigured {
                    Text("留下联系方式的话，我们处理后可以告诉你。")
                } else {
                    Text("会打开邮件 App，把内容发到 \(FeedbackClient.supportEmail)。")
                }
            }
        }
        .navigationTitle(category == .recipe ? "菜谱投稿" : "意见反馈")
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
            Button("去 GitHub") { openURL(URL(string: "https://github.com/YunYouJun/cook/issues/new/choose")!) }
            Button("发邮件") { sendMail() }
        } message: {
            Text("内容已经复制。可以发邮件给我们，或者到 GitHub 提一个 issue。")
        }
        .alert("没有找到邮件 App", isPresented: $showingNoMail) {
            Button("好", role: .cancel) {}
        } message: {
            Text("邮箱 \(FeedbackClient.supportEmail) 和反馈内容都已复制，可以用其他邮箱发给我们。")
        }
        .sensoryFeedback(.success, trigger: showingSent) { _, sent in sent }
    }

    private func send() {
        guard feedback.isConfigured else {
            sendMail()
            return
        }
        sending = true
        Task {
            defer { sending = false }
            do {
                try await feedback.send(category, note: note, contact: trimmedContact)
                showingSent = true
            } catch {
                UIPasteboard.general.string = trimmedNote
                showingFailed = true
            }
        }
    }

    /// 没装或没设置邮件 App 时 openURL 不会成功，这时把邮箱和内容复制下来
    private func sendMail() {
        openURL(mailURL) { accepted in
            if !accepted {
                UIPasteboard.general.string = "\(FeedbackClient.supportEmail)\n\n\(trimmedNote)"
                showingNoMail = true
            }
        }
    }
}
