import CoreTransferable
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

/// 云乐坊身份与 iCloud 数据同步分开管理，文件导入只增加收藏和历史。
struct DataSyncSettingsView: View {
    @Environment(RecipeStore.self) private var store
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
                Label("使用系统 iCloud 账户", systemImage: "icloud")
            } header: {
                Text("Apple 设备间同步")
            } footer: {
                Text("iCloud 可用时，收藏、浏览记录、做菜偏好、冰箱和私人菜谱会在同一 Apple 账户的设备间同步。云乐坊账号的登录和退出不会改变这些数据。")
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
        }
        .navigationTitle("数据与同步")
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
