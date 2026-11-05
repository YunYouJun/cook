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
            switch snapshot {
            case let .success(data):
                Section("我的菜谱 · \(data.recipes.count)") {
                    ForEach(data.recipes.filter { query.isEmpty || $0.name.contains(query) || $0.stuff.contains(where: { $0.contains(query) }) }) { recipe in
                        if let source = recipe.sourceURL, recipe.video == nil {
                            HStack {
                                Link(destination: source) { recipeLabel(recipe) }
                                    .buttonStyle(.borderless)
                                Spacer()
                                NavigationLink("详情") { PrivateRecipeDetailView(customId: recipe.customId) }
                                    .fixedSize()
                                    .accessibilityLabel("查看\(recipe.name)详情")
                            }
                        } else {
                            NavigationLink { PrivateRecipeDetailView(customId: recipe.customId) } label: { recipeLabel(recipe) }
                        }
                    }
                    if data.recipes.isEmpty {
                        ContentUnavailableView("还没有私人菜谱", systemImage: "book.closed", description: Text("点右上角的加号，记下自己的做法。"))
                    } else if !data.recipes.contains(where: { query.isEmpty || $0.name.contains(query) || $0.stuff.contains(where: { $0.contains(query) }) }) {
                        ContentUnavailableView.search(text: query)
                    }
                }
            case .failure:
                Text("有菜谱暂时无法读取，已保留原始数据。请勿清除 App 数据。").foregroundStyle(.red)
            }
            Section {
                DisclosureGroup("备份与迁移") {
                    Button("导出私人菜谱（文字与链接）", systemImage: "square.and.arrow.up") { export(withVideos: false) }
                    Button("导出视频关联备份（iOS）", systemImage: "video.badge.checkmark") { export(withVideos: true) }
                    Text("文字备份不含视频。关联备份可恢复同一 iCloud 中的视频关联，也不含视频文件；本机视频请在详情中另行导出，跨设备后重新关联。")
                        .font(.caption).foregroundStyle(.secondary)
                    Button("从文件导入", systemImage: "folder") { importing = true }
                    PasteButton(payloadType: String.self) { strings in
                        Task { @MainActor in read(strings.joined()) }
                    }
                }
            } footer: {
                Text("通过 iCloud 在你的 Apple 设备间同步；无法使用 iCloud 时保存在本机。与网页版通过文件迁移。")
            }
        }
        .navigationTitle("我的菜谱")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("新建菜谱", systemImage: "plus") { editor = RecipeEditorItem(recipe: nil) }
                    .labelStyle(.iconOnly)
            }
        }
        .modifier(PrivateRecipeSearch(query: $query))
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
        .onReceive(NotificationCenter.default.publisher(for: .privateVideoCleanupFailed)) { _ in
            message = "菜谱变更已保存，但旧视频副本未能清理。iCloud 副本可在“文件”中的云食册目录清理；本机副本可能继续保留。"
        }
        .alert("私人菜谱", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("好", role: .cancel) {}
        } message: { Text(message ?? "") }
    }

    private func recipeLabel(_ recipe: PrivateRecipe) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(recipe.name)
            Text(recipe.stuff.joined(separator: "、")).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func read(_ text: String) {
        do { incoming = RecipeImportItem(recipes: try PrivateRecipeNativeFile.parse(text)) }
        catch { message = error.localizedDescription }
    }

    private func export(withVideos: Bool) {
        do {
            let snapshot = try PrivateRecipePersistence.snapshot(in: context)
            exporting = PrivateRecipeDocument(text: try (withVideos ? PrivateRecipeNativeFile.export(recipes: snapshot.recipes, backup: true) : PrivateRecipeFile(recipes: snapshot.recipes).export()))
            showingExporter = true
        } catch { message = error.localizedDescription }
    }
}

private struct PrivateRecipeSearch: ViewModifier {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Binding var query: String

    func body(content: Content) -> some View {
        if horizontalSizeClass == .regular || ProcessInfo.processInfo.isiOSAppOnMac {
            content.toolbar {
                if #available(iOS 26.0, *) {
                    ToolbarItem(placement: .topBarTrailing) {
                        PrivateRecipeSearchField(query: $query)
                    }
                    .sharedBackgroundVisibility(.hidden)
                } else {
                    ToolbarItem(placement: .topBarTrailing) {
                        PrivateRecipeSearchField(query: $query)
                    }
                }
            }
        } else {
            content.searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "菜名或食材")
        }
    }
}

private struct PrivateRecipeSearchField: View {
    @Binding var query: String
    @FocusState private var isFocused: Bool

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            Button("搜索菜谱", systemImage: "magnifyingglass") { isFocused = true }
                .labelStyle(.iconOnly)
                .keyboardShortcut("f", modifiers: .command)

            TextField("菜名或食材", text: $query)
                .textFieldStyle(.plain)
                // 用输入框的自然高度参与居中，避免系统搜索栏内部的垂直偏移。
                .fixedSize(horizontal: false, vertical: true)
                .focused($isFocused)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit { isFocused = false }

            if !query.isEmpty {
                Button("清除搜索", systemImage: "xmark.circle.fill") {
                    query = ""
                    isFocused = true
                }
                .labelStyle(.iconOnly)
            }
        }
        .font(.body)
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(minWidth: 120, idealWidth: 180, maxWidth: 240)
        .frame(minHeight: 44)
        .contentShape(Capsule())
        .onTapGesture { isFocused = true }
        .onKeyPress(.escape) {
            query = ""
            isFocused = false
            return .handled
        }
        .floatingGlass(in: Capsule())
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
    @State private var removing = false
    @Environment(\.privateRecipeVideos) private var files

    private var recipe: PrivateRecipe? { (try? PrivateRecipeSnapshot(records))?.recipes.first { $0.customId == customId } }

    var body: some View {
        Group {
            if let recipe {
                List {
                    if let video = recipe.video { Section("做法视频") { PrivateRecipeVideoView(video: video).id(video.id) } }
                    Section("食材") { Text(recipe.stuff.joined(separator: "、")) }
                    Section("厨具") { Text(recipe.tools.isEmpty ? "未记录" : recipe.tools.joined(separator: "、")) }
                    if let url = recipe.sourceURL { Section("来源") { Link(destination: url) { Label(recipe.bv ?? recipe.link ?? "打开来源", systemImage: "arrow.up.right.square") } } }
                    if let note = recipe.note, !note.isEmpty { Section("做法与备注") { Text(note).textSelection(.enabled) } }
                    Section {
                        Button("编辑菜谱") { editor = RecipeEditorItem(recipe: recipe) }
                        Button("删除菜谱", role: .destructive) { deleting = true }
                    }
                }
                .disabled(removing)
                .navigationTitle(recipe.name)
                .confirmationDialog("删除这道私人菜谱？", isPresented: $deleting, titleVisibility: .visible) {
                    Button("删除", role: .destructive) {
                        removing = true
                        Task {
                            do {
                                try PrivateRecipePersistence.remove(recipe, in: context.container)
                                if let video = recipe.video {
                                    do { try await PrivateRecipeVideoPersistence.removeIfUnreferenced(video, in: context.container, files: files) }
                                    catch { NotificationCenter.default.post(name: .privateVideoCleanupFailed, object: nil) }
                                }
                                dismiss()
                            } catch { self.error = error.localizedDescription }
                            removing = false
                        }
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

struct PrivateRecipeEditorList {
    var text: String
    private var preserved: [String]?

    init(preserving original: [String]?) {
        preserved = original
        text = original?.joined(separator: "、") ?? ""
    }

    var values: [String] { PrivateRecipeEditorInput.list(text, preserving: preserved) }

    mutating func append(_ name: String, normalizer: StuffNormalizer? = nil) {
        let current = values
        let added = normalizer?.normalize([name]).first ?? name
        let existingNames = normalizer?.normalize(current) ?? current
        let next = existingNames.contains(added) ? current : current + [added]
        preserved = next
        text = next.joined(separator: "、")
    }
}

private struct PrivateRecipeEditor: View {
    let original: PrivateRecipe?
    @Environment(RecipeStore.self) private var store
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var stuff: PrivateRecipeEditorList
    @State private var tools: PrivateRecipeEditorList
    @State private var source: String
    @State private var note: String
    @State private var error: String?
    @State private var video: PrivateRecipeVideo?
    @State private var draft: PrivateVideoDraft?
    @State private var inCloud = false
    @State private var selectingVideo = false
    @State private var busy = false
    @State private var operation: Task<Void, Never>?
    @Environment(\.privateRecipeVideos) private var files

    init(original: PrivateRecipe?) {
        self.original = original
        _name = State(initialValue: original?.name ?? "")
        _stuff = State(initialValue: PrivateRecipeEditorList(preserving: original?.stuff))
        _tools = State(initialValue: PrivateRecipeEditorList(preserving: original?.tools))
        _source = State(initialValue: original?.bv.flatMap { $0.isEmpty ? nil : $0 } ?? original?.link ?? "")
        _note = State(initialValue: original?.note ?? "")
        _video = State(initialValue: original?.video)
        _inCloud = State(initialValue: original?.video?.location == .iCloud)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section { TextField("菜名（最多 30 字）", text: $name) }
                Section {
                    TextField("鸡蛋、丝瓜", text: $stuff.text, axis: .vertical)
                    Menu("从食材列表添加") {
                        ForEach(store.stuffItems, id: \.name) { item in
                            Button(item.name) { stuff.append(item.name, normalizer: store.data.normalizer) }
                        }
                    }
                } header: { Text("食材") } footer: { Text("用顿号、逗号或换行分隔，至少一种。") }
                Section {
                    TextField("电蒸锅", text: $tools.text, axis: .vertical)
                    Menu("从厨具列表添加") {
                        ForEach(store.food.tools, id: \.name) { item in
                            Button(item.name) { tools.append(item.name) }
                        }
                    }
                } header: { Text("厨具（可选）") } footer: { Text("可填写自己的食材和厨具名称，不会修改公共词典。") }
                Section("来源（可选）") {
                    TextField("BV 号或 http(s) 网页地址", text: $source).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                }
                Section {
                    if let video {
                        Label(video.originalName, systemImage: "video")
                        Text(ByteCountFormatter.string(fromByteCount: video.byteCount, countStyle: .file)).font(.caption).foregroundStyle(.secondary)
                    }
                    Button(video == nil ? "关联视频" : "替换视频", systemImage: "folder") { selectingVideo = true }
                    if video != nil {
                        Toggle("存入 iCloud Drive", isOn: $inCloud)
                            .disabled(draft == nil && video?.location == .iCloud)
                        Button("移除视频", role: .destructive) {
                            if let draft { Task { await files.discard(draft) } }
                            draft = nil
                            video = nil
                        }
                    }
                    if busy { ProgressView("正在处理视频…") }
                } header: { Text("做法视频（可选）") } footer: {
                    Text("MP4、MOV 或 M4V，最多 500 MB。保存时复制到云食册目录，原文件保留。开启后使用你自己的 iCloud Drive 空间；关闭时仅保存在这台设备。移除或删除菜谱会清理云食册管理的无引用副本。")
                }
                Section("做法与备注（可选）") { TextField("写下自己的做法", text: $note, axis: .vertical).lineLimit(6...16) }
                if let error { Text(error).foregroundStyle(.red) }
            }
            .disabled(busy)
            .navigationTitle(original == nil ? "新建菜谱" : "编辑菜谱")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { operation?.cancel(); dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("保存", action: save).disabled(busy) }
            }
            .interactiveDismissDisabled(busy)
            .task { if original?.video == nil { inCloud = await files.cloudAvailable() } }
            .fileImporter(isPresented: $selectingVideo, allowedContentTypes: [.mpeg4Movie, .quickTimeMovie, UTType(filenameExtension: "m4v")!]) { result in
                switch result {
                case let .success(url):
                    busy = true
                    operation = Task {
                        do {
                            let next = try await files.stage(url)
                            if Task.isCancelled { await files.discard(next); busy = false; return }
                            if let draft { await files.discard(draft) }
                            draft = next
                            video = next.video
                            error = nil
                        } catch is CancellationError { }
                        catch { self.error = error.localizedDescription }
                        busy = false
                    }
                case let .failure(error):
                    if (error as NSError).code != NSUserCancelledError { self.error = error.localizedDescription }
                }
            }
            .onDisappear {
                operation?.cancel()
                if let draft { Task { await files.discard(draft) } }
            }
        }
    }

    private func save() {
        do {
            let now = max(Int64(Date().timeIntervalSince1970 * 1000), (original?.updatedAt ?? 0) + 1)
            var recipe = original ?? PrivateRecipe(customId: "custom-" + UUID().uuidString.lowercased(), name: "", stuff: [], tools: [], createdAt: now, updatedAt: now)
            recipe.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
            recipe.stuff = stuff.values
            recipe.tools = tools.values
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
            recipe.video = video
            _ = try recipe.validated()
            busy = true
            operation = Task {
                do {
                    var pending = draft
                    if pending == nil, let video, video.location == .local, inCloud {
                        pending = try await files.stageExisting(video)
                        draft = pending
                    }
                    let cleaned = try await PrivateRecipeVideoPersistence.save(recipe, replacing: original, draft: pending, inCloud: inCloud, in: context.container, files: files)
                    if !cleaned { NotificationCenter.default.post(name: .privateVideoCleanupFailed, object: nil) }
                } catch {
                    if !(error is CancellationError) { self.error = error.localizedDescription }
                    busy = false
                    return
                }
                busy = false
                dismiss()
            }
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
                                    if let video = recipe.video { Text("视频：" + video.originalName).font(.caption) }
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
