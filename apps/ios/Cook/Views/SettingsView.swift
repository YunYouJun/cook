import CoreTransferable
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

/// 我的：做菜偏好、和网页版互通数据、反馈和关于
struct SettingsView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(FeedbackClient.self) private var feedback
    @Environment(\.modelContext) private var context
    // 导出要带上软删除的记录，网页版合并时才知道哪些被取消了
    @Query private var allFavorites: [Favorite]
    @Query private var allVisits: [HistoryEntry]
    @State private var importingFile = false
    @State private var importResult: ImportResult?

    private enum ImportResult: Identifiable {
        case done(favorites: Int, visits: Int)
        case failed
        case saveFailed

        var id: String {
            switch self {
            case let .done(favorites, visits): return "done-\(favorites)-\(visits)"
            case .failed: return "failed"
            case .saveFailed: return "saveFailed"
            }
        }
    }

    private var importTitle: String {
        switch importResult {
        case .failed?: return "导入失败"
        case .saveFailed?: return "导入未能保存"
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
                    NavigationLink("我的菜谱") { PrivateRecipesView() }
                    NavigationLink("食材和厨具") { PersonalCatalogView() }
                }
                Section {
                    PreferenceRows()
                } header: {
                    Text("做菜偏好")
                } footer: {
                    Text("登录 iCloud 后，收藏、浏览记录、做菜偏好和冰箱里的食材会在你的设备间同步。")
                }
                Section {
                    FridgeReminderRows()
                } header: {
                    Text("冰箱")
                } footer: {
                    Text("冰箱里的食材到期前一天和当天提醒一次。提醒只在这台设备上排，不经过服务器。")
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
                    NavigationLink("意见反馈") { AppFeedbackView() }
                    NavigationLink("菜谱投稿") { AppFeedbackView(category: .recipe) }
                    Link("需求墙", destination: URL(string: "https://support.yunle.fun/feedback?product=cook")!)
                    Link("支持中心", destination: URL(string: "https://support.yunle.fun/?product=cook")!)
                } header: {
                    Text("反馈")
                }
                Section {
                    NavigationLink("隐私政策") { PrivacyPolicyView() }
                    if feedback.isConfigured {
                        Toggle("帮助改进推荐", isOn: Binding(get: { feedback.signalsEnabled }, set: { feedback.setSignalConsent($0) }))
                            .accessibilityIdentifier("privacy.shareSignals")
                    }
                } header: {
                    Text("隐私")
                } footer: {
                    if feedback.isConfigured {
                        Text("默认关闭。开启表示同意向腾讯云 CloudBase 分享后续的食材组合、厨具、推荐方式和菜谱点击情况，以及 App 版本和每次启动的随机标识，用来补菜、调整排序和限制请求次数。不含输入原文、账号或设备硬件标识符。关闭不影响使用。详见隐私政策。")
                    }
                }
                Section("开发者") {
                    Link("在 GitHub 上反馈", destination: URL(string: "https://github.com/YunYouJun/cook/issues")!)
                    Link("参与讨论", destination: URL(string: "https://github.com/YunYouJun/cook/discussions")!)
                }
                Section("关于") {
                    LabeledContent("版本", value: appVersion)
                    LabeledContent("菜谱", value: "\(store.recipes.count) 道")
                    LabeledContent("数据版本", value: store.data.meta.dataVersion)
                    Link("更新日志", destination: URL(string: "https://docs.yunyoujun.cn/projects/cook/changelog")!)
                    Link("网页版：食用手册", destination: URL(string: "https://cook.yunyoujun.cn")!)
                }
            }
            .accessibilityIdentifier("settings.list")
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
                case .saveFailed:
                    Text("数据已识别，但没能保存到设备。原有收藏和浏览记录已保留，请检查可用储存空间后重新导入。")
                }
            }
            .sensoryFeedback(trigger: importResult?.id) { _, id in
                guard let id else {
                    return nil
                }
                return id == "failed" || id == "saveFailed" ? .error : .success
            }
        }
    }

    private func importText(_ text: String) {
        guard let data = WebUserData.parse(text) else {
            importResult = .failed
            return
        }
        do {
            let added = try context.importWebData(data, recipeIdByName: store.recipeIdByName, validIds: Set(store.recipesById.keys))
            importResult = .done(favorites: added.favorites, visits: added.visits)
        } catch {
            importResult = .saveFailed
        }
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
