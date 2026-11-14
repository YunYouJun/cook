import SwiftData
import SwiftUI

struct FridgeCategoryPicker: View {
    @Query(sort: \FridgeCategory.name) private var categories: [FridgeCategory]
    @State private var creating = false
    @Binding var selection: FridgeCategoryChoice
    var automaticTitle: String?

    var body: some View {
        Picker("分类", selection: availableSelection) {
            Text(automaticTitle.map { "自动归类（\($0)）" } ?? "自动归类").tag(FridgeCategoryChoice.automatic)
            ForEach(FridgeBuiltInCategory.allCases, id: \.self) { category in
                Text(category.title).tag(FridgeCategoryChoice.builtIn(category))
            }
            ForEach(categories) { category in
                Text(category.name).tag(FridgeCategoryChoice.custom(category.identifier))
            }
        }
        .accessibilityIdentifier("fridge.category.picker")
        Button("新建分类", systemImage: "plus") { creating = true }
            .sheet(isPresented: $creating) {
                FridgeCategoryEditor { selection = .custom($0.identifier) }
            }
    }

    private var availableSelection: Binding<FridgeCategoryChoice> {
        Binding(get: { selection.available(in: categories) }, set: { selection = $0 })
    }
}

struct FridgeCategoryManager: View {
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \FridgeCategory.name) private var categories: [FridgeCategory]
    @State private var editor: EditorRoute?
    @State private var deleting: FridgeCategory?
    @State private var saveError = false

    private enum EditorRoute: Identifiable {
        case new, edit(FridgeCategory)

        var id: ID {
            switch self {
            case .new: return .new
            case let .edit(category): return .edit(category.identifier)
            }
        }

        enum ID: Hashable { case new, edit(UUID) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section("自定义分类") {
                    ForEach(categories) { category in
                        Button(category.name) { editor = .edit(category) }
                            .foregroundStyle(.primary)
                            .accessibilityHint("重命名分类")
                            .swipeActions(allowsFullSwipe: false) {
                                Button("删除", systemImage: "trash", role: .destructive) { deleting = category }
                            }
                    }
                    Button("新建分类", systemImage: "plus") { editor = .new }
                }
            }
            .navigationTitle("管理分类")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
            .sheet(item: $editor) { route in
                switch route {
                case .new: FridgeCategoryEditor()
                case let .edit(category): FridgeCategoryEditor(category: category)
                }
            }
            .alert("删除分类？", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { category in
                Button("删除分类", role: .destructive) { delete(category) }
                Button("取消", role: .cancel) {}
            } message: { category in
                Text("“\(category.name)”里的食材会保留，并恢复自动归类。")
            }
            .fridgeSaveError(isPresented: $saveError)
        }
    }

    private func delete(_ category: FridgeCategory) {
        do {
            try context.saveUserChange { context.delete(category) }
        } catch {
            saveError = true
        }
    }
}

private struct FridgeCategoryEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var context
    @Query private var categories: [FridgeCategory]
    @State private var name: String
    @State private var saveError = false
    private let category: FridgeCategory?
    private let onSave: (FridgeCategory) -> Void

    init(category: FridgeCategory? = nil, onSave: @escaping (FridgeCategory) -> Void = { _ in }) {
        self.category = category
        self.onSave = onSave
        _name = State(initialValue: category?.name ?? "")
    }

    private var cleanName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var nameError: String? {
        FridgeCategory.nameError(cleanName, takenNames: categories.filter { $0.identifier != category?.identifier }.map(\.name))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("分类名称", text: $name, prompt: Text("例如：早餐、零食"))
                        .accessibilityIdentifier("fridge.category.name")
                        .submitLabel(.done)
                        .onSubmit(save)
                } footer: {
                    if !cleanName.isEmpty, let nameError { Text(nameError) }
                }
            }
            .navigationTitle(category == nil ? "新建分类" : "重命名分类")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("保存", action: save).disabled(nameError != nil) }
            }
            .fridgeSaveError(isPresented: $saveError)
        }
    }

    private func save() {
        guard nameError == nil else { return }
        do {
            let saved = try context.saveUserChange {
                if let category {
                    category.name = cleanName
                    return category
                }
                let created = FridgeCategory(name: cleanName)
                context.insert(created)
                return created
            }
            onSave(saved)
            dismiss()
        } catch {
            saveError = true
        }
    }
}

extension View {
    func fridgeSaveError(isPresented: Binding<Bool>) -> some View {
        alert("未能保存修改", isPresented: isPresented) {
            Button("好") {}
        } message: {
            Text("请检查设备的可用储存空间后重试。")
        }
    }
}
