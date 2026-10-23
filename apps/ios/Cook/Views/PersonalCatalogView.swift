import CookCore
import SwiftUI
import UniformTypeIdentifiers

struct PersonalCatalogView: View {
    @Environment(RecipeStore.self) private var store
    @State private var category: CatalogCategory = .vegetable
    @State private var name = ""
    @State private var error: String?
    @State private var importing = false
    @State private var exporting = false
    @State private var document = PrivateRecipeDocument(text: "")
    @State private var incoming: PersonalCatalog?
    @FocusState private var editingName: Bool

    var body: some View {
        List {
            if let error = store.catalog.loadError {
                Section { Text(error); Button("重试读取") { store.catalog.reload() } }
            }
            Section {
                Picker("类别", selection: $category) {
                    ForEach(CatalogCategory.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                TextField("名称（最多 30 字）", text: $name)
                    .focused($editingName)
                    .onSubmit(add)
                Button("添加条目", action: add)
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.catalog.loadError != nil)
            } footer: {
                Text("隐藏和删除只影响选择列表，已有菜谱、冰箱和忌口保留。配置只保存在本机，可通过文件在 Web 和 iOS 间迁移。")
            }
            ForEach(CatalogCategory.allCases, id: \.self) { group in
                Section(group.title) {
                    ForEach(store.catalog.value.items.filter { $0.category == group }) { entry in
                        HStack {
                            Text(entry.name)
                            Spacer()
                            Button("删除", role: .destructive) {
                                change { value in var next = value; next.items.removeAll { $0.id == entry.id }; return next }
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("删除\(entry.name)")
                        }
                    }
                    ForEach(store.data.food.items(in: group), id: \.name) { item in
                        let entry = CatalogEntry(category: group, name: item.name)
                        let hidden = store.catalog.value.hidden.contains(entry)
                        HStack {
                            Text("\(item.emoji) \(item.label ?? item.name)")
                            Spacer()
                            Button(hidden ? "恢复" : "隐藏") {
                                change { value in
                                    var next = value
                                    if hidden { next.hidden.removeAll { $0 == entry } } else { next.hidden.append(entry) }
                                    return next
                                }
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("\(hidden ? "恢复" : "隐藏")\(item.name)")
                        }
                    }
                }
            }
            Section("备份与迁移") {
                Button("导出食材和厨具") {
                    do { document = PrivateRecipeDocument(text: try store.catalog.value.export()); exporting = true }
                    catch { self.error = error.localizedDescription }
                }.disabled(store.catalog.loadError != nil)
                Button("导入食材和厨具") { importing = true }
            }
        }
        .navigationTitle("食材和厨具")
        .fileExporter(isPresented: $exporting, document: document, contentType: .json, defaultFilename: "cook-personal-catalog") { result in
            if case let .failure(error) = result { self.error = error.localizedDescription }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            do {
                let url = try result.get()
                let granted = url.startAccessingSecurityScopedResource()
                defer { if granted { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size <= PersonalCatalog.fileLimit else { throw PersonalCatalogError.tooLarge }
                incoming = try PersonalCatalog.parse(String(contentsOf: url, encoding: .utf8))
            } catch { self.error = error.localizedDescription }
        }
        .confirmationDialog("合并食材和厨具？", isPresented: Binding(get: { incoming != nil }, set: { if !$0 { incoming = nil } }), titleVisibility: .visible) {
            Button("确认合并") {
                guard let incoming else { return }
                change { try $0.merging(incoming) }
                self.incoming = nil
            }
            Button("取消", role: .cancel) { incoming = nil }
        } message: {
            Text("将合并 \(incoming?.items.count ?? 0) 个自定义条目和 \(incoming?.hidden.count ?? 0) 个隐藏偏好。重复名称保留本机类别，已有菜谱和忌口不变。")
        }
        .alert("未能完成", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("好", role: .cancel) { error = nil }
        } message: { Text(error ?? "") }
    }

    private func change(_ action: (PersonalCatalog) throws -> PersonalCatalog) {
        do { try store.catalog.update(action) } catch { self.error = error.localizedDescription }
    }
    private func add() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalized = category == .tools ? trimmed : (store.data.normalizer.unique([trimmed]).first ?? "")
        let entry = CatalogEntry(category: category, name: normalized)
        do {
            try store.catalog.update { current in
                let builtins = CatalogCategory.allCases.flatMap { group in store.data.food.items(in: group).map { CatalogEntry(category: group, name: $0.name) } }
                guard !(current.items + builtins).contains(where: { $0.id == entry.id }) else { throw PersonalCatalogError.duplicate }
                var next = current; next.items.append(entry); return next
            }
            name = ""
            editingName = false
        } catch { self.error = error.localizedDescription }
    }
}
