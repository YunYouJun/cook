import CookCore
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct PrivateRecipesView: View {
    @Environment(\.modelContext) private var context
    @Query private var records: [PrivateRecipeRecord]
    @State private var editor: RecipeEditorItem?
    @State private var incoming: RecipeImportItem?
    @State private var exporting: PrivateRecipeDocument?
    @State private var showingExporter = false
    @State private var importing = false
    @State private var message: String?
    @State private var query = ""

    private var snapshot: Result<PrivateRecipeSnapshot, Error> { Result { try PrivateRecipeSnapshot(records) } }

    var body: some View {
        List {
            Section {
                Button("新建菜谱", systemImage: "plus") { editor = RecipeEditorItem(recipe: nil) }
                Button("导出私人菜谱", systemImage: "square.and.arrow.up", action: export)
                Button("从文件导入", systemImage: "folder") { importing = true }
                PasteButton(payloadType: String.self) { strings in
                    Task { @MainActor in read(strings.joined()) }
                }
            } footer: {
                Text("私人菜谱通过 iCloud 在同一 Apple 账号的 Apple 设备间同步；与网页版通过文件迁移。尚未登录或无法使用 iCloud 时保存在本机。")
            }
            switch snapshot {
            case let .success(data):
                Section("我的菜谱 · \(data.recipes.count)") {
                    ForEach(data.recipes.filter { query.isEmpty || $0.name.contains(query) || $0.stuff.contains(where: { $0.contains(query) }) }) { recipe in
                        NavigationLink {
                            PrivateRecipeDetailView(customId: recipe.customId)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(recipe.name)
                                Text(recipe.stuff.joined(separator: "、")).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    if data.recipes.isEmpty { Text("记下自己的做法，或导入网页版备份。给菜谱添加的食材和厨具只属于这道菜谱。").foregroundStyle(.secondary) }
                }
            case .failure:
                Text("有菜谱暂时无法读取，已保留原始数据。请勿清除 App 数据。").foregroundStyle(.red)
            }
        }
        .navigationTitle("我的菜谱")
        .searchable(text: $query, prompt: "菜名或食材")
        .sheet(item: $editor) { item in PrivateRecipeEditor(original: item.recipe) }
        .sheet(item: $incoming) { item in PrivateRecipeImportView(recipes: item.recipes) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .plainText]) { result in
            do {
                let url = try result.get()
                let access = url.startAccessingSecurityScopedResource()
                defer { if access { url.stopAccessingSecurityScopedResource() } }
                let handle = try FileHandle(forReadingFrom: url)
                defer { try? handle.close() }
                let data = try handle.read(upToCount: PrivateRecipeFile.byteLimit + 1) ?? Data()
                guard data.count <= PrivateRecipeFile.byteLimit else { throw PrivateRecipeFileError.tooLarge }
                guard let text = String(data: data, encoding: .utf8) else { throw PrivateRecipeFileError.invalid }
                read(text)
            } catch { message = error.localizedDescription }
        }
        .fileExporter(isPresented: $showingExporter, document: exporting, contentType: .json, defaultFilename: "cook-custom-recipes") { result in
            if case let .failure(error) = result { message = error.localizedDescription }
        }
        .alert("私人菜谱", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(message ?? "") }
    }

    private func read(_ text: String) {
        do { incoming = RecipeImportItem(recipes: try PrivateRecipeFile.parse(text).recipes) }
        catch { message = error.localizedDescription }
    }

    private func export() {
        do {
            let snapshot = try PrivateRecipePersistence.snapshot(in: context)
            exporting = PrivateRecipeDocument(text: try PrivateRecipeFile(recipes: snapshot.recipes).export())
            showingExporter = true
        } catch { message = error.localizedDescription }
    }
}

private struct RecipeEditorItem: Identifiable {
    let id = UUID()
    let recipe: PrivateRecipe?
}
private struct RecipeImportItem: Identifiable {
    let id = UUID()
    let recipes: [PrivateRecipe]
}

struct PrivateRecipeDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents, let text = String(data: data, encoding: .utf8) else { throw PrivateRecipeFileError.invalid }
        self.text = text
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

private struct PrivateRecipeDetailView: View {
    let customId: String
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query private var records: [PrivateRecipeRecord]
    @State private var editor: RecipeEditorItem?
    @State private var deleting = false
    @State private var error: String?

    private var recipe: PrivateRecipe? { (try? PrivateRecipeSnapshot(records))?.recipes.first { $0.customId == customId } }

    var body: some View {
        Group {
            if let recipe {
                List {
                    Section("食材") { Text(recipe.stuff.joined(separator: "、")) }
                    Section("厨具") { Text(recipe.tools.isEmpty ? "未记录" : recipe.tools.joined(separator: "、")) }
                    if let url = recipe.sourceURL { Section("来源") { Link(destination: url) { Label(recipe.bv ?? recipe.link ?? "打开来源", systemImage: "arrow.up.right.square") } } }
                    if let note = recipe.note, !note.isEmpty { Section("做法与备注") { Text(note).textSelection(.enabled) } }
                    Section {
                        Button("编辑菜谱") { editor = RecipeEditorItem(recipe: recipe) }
                        Button("删除菜谱", role: .destructive) { deleting = true }
                    }
                }
                .navigationTitle(recipe.name)
                .confirmationDialog("删除这道私人菜谱？", isPresented: $deleting, titleVisibility: .visible) {
                    Button("删除", role: .destructive) {
                        do { try PrivateRecipePersistence.remove(recipe, in: context.container); dismiss() }
                        catch { self.error = error.localizedDescription }
                    }
                } message: { Text("删除会通过 iCloud 同步到你的其他 Apple 设备。导出的备份文件不受影响。") }
            } else { ContentUnavailableView("菜谱暂不可用", systemImage: "doc", description: Text("可能已在其他设备删除，或数据暂时无法读取。")) }
        }
        .sheet(item: $editor) { item in PrivateRecipeEditor(original: item.recipe) }
        .alert("未能保存", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) { Button("好", role: .cancel) {} } message: { Text(error ?? "") }
    }
}

enum PrivateRecipeEditorInput {
    static func list(_ text: String, preserving original: [String]?) -> [String] {
        if let original, text == original.joined(separator: "、") { return original }
        return uniqueStrings(text.components(separatedBy: CharacterSet(charactersIn: "、,，\n")).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })
    }
}

private struct PrivateRecipeEditor: View {
    let original: PrivateRecipe?
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var stuff: String
    @State private var tools: String
    @State private var source: String
    @State private var note: String
    @State private var error: String?

    init(original: PrivateRecipe?) {
        self.original = original
        _name = State(initialValue: original?.name ?? "")
        _stuff = State(initialValue: original?.stuff.joined(separator: "、") ?? "")
        _tools = State(initialValue: original?.tools.joined(separator: "、") ?? "")
        _source = State(initialValue: original?.bv.flatMap { $0.isEmpty ? nil : $0 } ?? original?.link ?? "")
        _note = State(initialValue: original?.note ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section { TextField("菜名（最多 30 字）", text: $name) }
                Section { TextField("鸡蛋、丝瓜", text: $stuff, axis: .vertical) } header: { Text("食材") } footer: { Text("用顿号、逗号或换行分隔，至少一种。") }
                Section { TextField("电蒸锅", text: $tools, axis: .vertical) } header: { Text("厨具（可选）") } footer: { Text("可填写自己的食材和厨具名称，不会修改公共词典。") }
                Section("来源（可选）") {
                    TextField("BV 号或 http(s) 网页地址", text: $source).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                }
                Section("做法与备注（可选）") { TextField("写下自己的做法", text: $note, axis: .vertical).lineLimit(6...16) }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle(original == nil ? "新建菜谱" : "编辑菜谱")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("保存", action: save) }
            }
        }
    }

    private func save() {
        do {
            let now = max(Int64(Date().timeIntervalSince1970 * 1000), (original?.updatedAt ?? 0) + 1)
            var recipe = original ?? PrivateRecipe(customId: "custom-" + UUID().uuidString.lowercased(), name: "", stuff: [], tools: [], createdAt: now, updatedAt: now)
            recipe.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
            recipe.stuff = PrivateRecipeEditorInput.list(stuff, preserving: original?.stuff)
            recipe.tools = PrivateRecipeEditorInput.list(tools, preserving: original?.tools)
            recipe.note = note
            recipe.updatedAt = now
            let source = source.trimmingCharacters(in: .whitespacesAndNewlines)
            // Preserve both source fields on an unchanged import; changing the source intentionally replaces them.
            if source != (original?.bv.flatMap { $0.isEmpty ? nil : $0 } ?? original?.link ?? "") {
                recipe.bv = nil
                recipe.link = nil
                if !source.isEmpty {
                    if source.range(of: "^BV[0-9a-z]{10}$", options: [.regularExpression, .caseInsensitive]) != nil { recipe.bv = source }
                    else if PrivateRecipeFile.isSafeURL(source) { recipe.link = source }
                    else { throw PrivateRecipeFileError.invalid }
                }
            }
            try PrivateRecipePersistence.save(recipe, replacing: original, in: context.container)
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

private struct PrivateRecipeImportView: View {
    let recipes: [PrivateRecipe]
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query private var records: [PrivateRecipeRecord]
    @State private var keepCopies = false
    @State private var error: String?

    private var plan: PrivateRecipeImport? {
        guard let data = try? PrivateRecipeSnapshot(records) else { return nil }
        return PrivateRecipeImport(current: data.recipes, incoming: recipes, deletedIDs: data.deletedIDs)
    }

    var body: some View {
        NavigationStack {
            List {
                if let plan {
                    Section { Text("新增 \(plan.additions.count) 道，相同 \(plan.duplicates.count) 道，冲突 \(plan.conflicts.count) 道。不会覆盖现有菜谱。") }
                    if !plan.conflicts.isEmpty {
                        Section {
                            Toggle("冲突菜谱另存副本", isOn: $keepCopies)
                            ForEach(plan.conflicts) { recipe in
                                VStack(alignment: .leading) {
                                    Text(recipe.name)
                                    Text(recipe.stuff.joined(separator: "、")).font(.caption)
                                    Text(recipe.tools.joined(separator: "、")).font(.caption)
                                    Text(recipe.bv ?? recipe.link ?? "无来源链接").font(.caption)
                                    Text(recipe.note ?? "无备注").font(.caption)
                                }
                            }
                        } footer: { Text("关闭时跳过冲突；打开时保留两份。重复导入相同副本不会再次新增。") }
                    }
                    Section("新增") { ForEach(plan.additions) { Text($0.name) } }
                } else { Text("本机数据暂时无法读取，原始数据已保留。") }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .navigationTitle("导入预览")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("确认导入") {
                        do {
                            _ = try PrivateRecipePersistence.importRecipes(recipes, keepCopies: keepCopies, in: context.container)
                            dismiss()
                        } catch { self.error = error.localizedDescription }
                    }.disabled(plan == nil)
                }
            }
        }
    }
}
