import CookCore
import SwiftUI
import UniformTypeIdentifiers

struct PersonalCatalogView: View {
    @Environment(RecipeStore.self) private var store
    @State private var category: CatalogCategory = .vegetable
    @State private var query = ""
    @State private var adding = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var error: String?
    @State private var importing = false
    @State private var exporting = false
    @State private var document = PrivateRecipeDocument(text: "")
    @State private var incoming: PersonalCatalog?

    var body: some View {
        List {
            if let error = store.catalog.loadError {
                Section { Text(error); Button("重试读取") { store.catalog.reload() } }
            }
            Section {
                Picker("类别", selection: $category) {
                    ForEach(CatalogCategory.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .modifier(CatalogPickerStyle(accessibilitySize: dynamicTypeSize.isAccessibilitySize))
            } footer: {
                Text("隐藏的条目不在选择列表中显示，已有菜谱和冰箱不受影响。")
            }
            let custom = store.catalog.value.items.filter { $0.category == category && matches($0.name) }
            let builtins = store.data.food.items(in: category).filter { matches($0.name) || matches($0.label ?? $0.name) }
            if !custom.isEmpty {
                Section("自定义") {
                    ForEach(custom) { entry in
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
                }
            }
            Section(category.title) {
                ForEach(builtins, id: \.name) { item in
                    let entry = CatalogEntry(category: category, name: item.name)
                    let hidden = store.catalog.value.hidden.contains(entry)
                    HStack {
                        Text("\(item.emoji) \(item.label ?? item.name)")
                            .foregroundStyle(hidden ? .secondary : .primary)
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
                if custom.isEmpty && builtins.isEmpty {
                    ContentUnavailableView.search(text: query)
                }
            }
        }
        .navigationTitle("食材和厨具")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索当前分类")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu("备份与迁移", systemImage: "ellipsis") {
                    Button("导出食材和厨具", systemImage: "square.and.arrow.up") {
                        do { document = PrivateRecipeDocument(text: try store.catalog.value.export()); exporting = true }
                        catch { self.error = error.localizedDescription }
                    }.disabled(store.catalog.loadError != nil)
                    Button("导入食材和厨具", systemImage: "square.and.arrow.down") { importing = true }
                    Text("配置只保存在本机，可通过文件在 Web 和 iOS 间迁移。")
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Button("添加条目", systemImage: "plus") { adding = true }
                    .disabled(store.catalog.loadError != nil)
            }
        }
        .sheet(isPresented: $adding) { CatalogEntryEditor(category: category) }
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
    private func matches(_ name: String) -> Bool {
        query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || name.localizedStandardContains(query.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

private struct CatalogPickerStyle: ViewModifier {
    let accessibilitySize: Bool
    @ViewBuilder func body(content: Content) -> some View {
        if accessibilitySize { content.pickerStyle(.menu) }
        else { content.pickerStyle(.segmented) }
    }
}

private struct CatalogEntryEditor: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State var category: CatalogCategory
    @State private var name = ""
    @State private var error: String?
    @FocusState private var editingName: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("名称（最多 30 字）", text: $name)
                        .focused($editingName)
                        .onSubmit(add)
                    Picker("类别", selection: $category) {
                        ForEach(CatalogCategory.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                } footer: {
                    Text("添加后可在做菜、冰箱和私人菜谱中选用。")
                }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("添加条目")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("添加", action: add)
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || store.catalog.loadError != nil)
                }
            }
            .onAppear { editingName = true }
        }
    }

    private func add() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let normalized = category == .tools ? trimmed : (store.data.normalizer.unique([trimmed]).first ?? "")
        let entry = CatalogEntry(category: category, name: normalized)
        do {
            try store.catalog.update { current in
                let builtins = CatalogCategory.allCases.flatMap { group in store.data.food.items(in: group).map { CatalogEntry(category: group, name: $0.name) } }
                guard !(current.items + builtins).contains(where: { $0.id == entry.id }) else { throw PersonalCatalogError.duplicate }
                var next = current; next.items.append(entry); return next
            }
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}
