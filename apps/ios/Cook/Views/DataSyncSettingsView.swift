import CoreTransferable
import CookCore
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct DataSyncSettingsView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.accountUserData) private var accountData
    @Environment(\.modelContext) private var context

    var body: some View {
        List {
            if let accountData {
                AccountSyncSettingsSection(data: accountData) {
                    let favorites = try context.fetch(FetchDescriptor<Favorite>())
                    let visits = try context.fetch(FetchDescriptor<HistoryEntry>())
                    let text = WebUserData.export(favorites: favorites, visits: visits, recipesById: store.recipesById)
                    var value = try UserDataDocument.decode(Data(text.utf8))
                    value.favoriteRemovals = []
                    value.historyRemovals = []
                    try accountData.importDocument(value)
                }
            }
            Section {
                SettingsRowLabel("Apple 设备间同步", symbol: "icloud.fill", color: .blue,
                                 subtitle: "使用系统 iCloud 账户")
            } header: {
                Text("iCloud")
            } footer: {
                Text("iCloud 可用时，同一 Apple 账户的设备会同步本机的收藏、浏览记录、做菜偏好、冰箱和私人菜谱。云乐坊账号的登录和退出不会改变这些数据。")
            }
            Section("手动迁移") {
                NavigationLink {
                    WebDataTransferView()
                } label: {
                    SettingsRowLabel("导入与导出", symbol: "arrow.left.arrow.right", color: .indigo,
                                     subtitle: "在 App 和网页版间迁移收藏和浏览记录")
                }
                .accessibilityIdentifier("dataSync.webTransfer")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("数据与同步")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct WebDataTransferView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.accountUserData) private var accountData
    @Environment(\.modelContext) private var context
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

    var body: some View {
        List {
            Section {
                Button("从文件导入", systemImage: "folder") { importingFile = true }
                LabeledContent {
                    PasteButton(payloadType: String.self) { strings in
                        Task { @MainActor in importText(strings.joined()) }
                    }
                    .labelStyle(.titleOnly)
                    .accessibilityLabel("粘贴并导入网页版数据")
                    .accessibilityIdentifier("webTransfer.paste")
                } label: {
                    Label("从剪贴板导入", systemImage: "doc.on.clipboard")
                }
            } header: {
                Text("从网页版导入")
            } footer: {
                Text("在网页版「我的 → 设置 → 收藏和历史」导出文件或复制到剪贴板，再选上方对应的方式。已有的收藏和浏览记录会保留。")
            }
            Section {
                ShareLink(
                    item: WebExportFile(text: exportText),
                    preview: SharePreview("云食册的收藏和浏览记录")
                ) {
                    Label("共享导出文件", systemImage: "square.and.arrow.up")
                }
            } header: {
                Text("导出到网页版")
            } footer: {
                Text("保存或发送当前收藏和浏览记录的文件，再到网页版选择「从文件导入」。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("导入与导出")
        .navigationBarTitleDisplayMode(.inline)
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

    private func importText(_ text: String) {
        guard let data = WebUserData.parse(text) else {
            importResult = .failed
            return
        }
        do {
            if let accountData, accountData.usesAccountLibrary {
                var additions = UserDataDocument()
                additions.favorites = data.favorites.map { .init(id: $0.recipeId, time: $0.timeMillis) }
                additions.histories = data.visits.compactMap { visit in
                    guard let id = store.recipeIdByName[visit.name], let recipe = store.recipesById[id] else { return nil }
                    return .init(recipe: .init(recipe: recipe), time: visit.timeMillis)
                }
                let before = accountData.snapshot.document
                try accountData.importDocument(additions)
                let after = accountData.snapshot.document
                importResult = .done(favorites: max(0, after.favorites.count - before.favorites.count), visits: max(0, after.histories.count - before.histories.count))
                return
            }
            let added = try context.importWebData(data, recipeIdByName: store.recipeIdByName, validIds: Set(store.recipesById.keys))
            importResult = .done(favorites: added.favorites, visits: added.visits)
        } catch {
            importResult = .saveFailed
        }
    }

    private var exportText: String {
        if let accountData, accountData.usesAccountLibrary { return accountData.exportText() }
        return WebUserData.export(favorites: allFavorites, visits: allVisits, recipesById: store.recipesById)
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
