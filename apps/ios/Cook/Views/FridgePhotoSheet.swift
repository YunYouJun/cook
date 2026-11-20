import AVFoundation
import CookCore
import PhotosUI
import SwiftData
import SwiftUI
import UIKit

/// 同一次选图可用于本机单份录入或经用户确认的会员批量识图。
struct FridgePhotoSheet: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var selectedPhoto: PhotosPickerItem?
    @State private var input: ScanInput?
    @State private var result: FridgeLabelScan?
    @State private var loadingPhoto = false
    @State private var recognizing = false
    @State private var showingCamera = false
    @State private var cameraData: Data?
    @State private var cameraDenied = false
    @State private var error: String?
    @State private var showingBatch = false
    @State private var showingSingleItem = false
    #if DEBUG
        @State private var fixtureLoaded = false
    #endif

    private struct ScanInput {
        let id = UUID()
        let data: Data
    }

    private var canUseBatch: Bool { FridgeVisionSheet.isAvailable && input != nil }
    private var suggestsBatch: Bool { canUseBatch && result?.suggestsBatchReview == true && !showingSingleItem }

    var body: some View {
        NavigationStack {
            Group {
                if let result {
                    if suggestsBatch {
                        batchSuggestion
                    } else {
                        FridgePhotoReview(scan: result, onRetake: resetPhoto,
                                          onBatch: canUseBatch ? { showingBatch = true } : nil)
                            .id(result.id)
                    }
                } else {
                    Form {
                        Section {
                            Button("拍一张照片", systemImage: "camera", action: openCamera)
                                .accessibilityIdentifier("fridge.camera")
                            PhotosPicker(selection: $selectedPhoto, matching: .images, preferredItemEncoding: .compatible) {
                                Label("从照片选择", systemImage: "photo")
                            }
                            .accessibilityIdentifier("fridge.photoLibrary")
                            if loadingPhoto || recognizing {
                                ProgressView(loadingPhoto ? "正在读取照片…" : "正在识别食材和日期…")
                            }
                        } header: {
                            Text(FridgeVisionSheet.isAvailable ? "拍食材或包装" : "拍包装或一份蔬果")
                        } footer: {
                            if FridgeVisionSheet.isAvailable {
                                Text("先在本机识别食材和日期。照片里有多种食材时，可用同一张照片继续批量识图。")
                            } else {
                                Text("包装请拍清生产日期、保质期或到期日。一次识别一份食材；照片仅在本机处理，不保存或上传。")
                            }
                        }
                        .disabled(loadingPhoto || recognizing)

                        if let error {
                            Section { Text(error).foregroundStyle(.red) }
                        }
                        if !loadingPhoto, !recognizing {
                            Section {
                                Text("识别后可核对食材和日期，再放进冰箱。蔬果没有包装日期时，可按种类生成建议吃完日期。")
                                    .foregroundStyle(.secondary)
                            }
                            FridgeProduceExamples()
                        }
                    }
                }
            }
            .navigationTitle(result == nil ? "拍照识别" : suggestsBatch ? "选择录入方式" : "确认识别结果")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
            .navigationDestination(isPresented: $showingBatch) {
                if let input {
                    FridgeVisionSheet(sourcePhoto: input.data, onUseOffline: showSingleItem,
                                      onRetake: resetPhoto, onSaved: { dismiss() })
                }
            }
            .fullScreenCover(
                isPresented: $showingCamera,
                onDismiss: {
                    if let cameraData {
                        input = ScanInput(data: cameraData)
                        self.cameraData = nil
                    }
                }
            ) {
                FridgeCamera { data in cameraData = data }
                    .ignoresSafeArea()
            }
            .alert("相机没有打开", isPresented: $cameraDenied) {
                Button("取消", role: .cancel) {}
                Button("去设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            } message: {
                Text("允许相机权限后可以拍照，也可以直接从照片选择。")
            }
            .task(id: selectedPhoto) {
                guard let selectedPhoto else { return }
                loadingPhoto = true
                error = nil
                defer { loadingPhoto = false }
                do {
                    guard let data = try await selectedPhoto.loadTransferable(type: Data.self) else {
                        throw FridgePhotoRecognition.Failure.unreadableImage
                    }
                    try Task.checkCancellation()
                    input = ScanInput(data: data)
                } catch is CancellationError {
                    return
                } catch {
                    guard !Task.isCancelled else { return }
                    self.error = "照片没有读取成功，请重试或换一张照片。"
                    self.selectedPhoto = nil
                }
            }
            .task(id: input?.id) {
                guard let input else { return }
                recognizing = true
                error = nil
                defer { recognizing = false }
                do {
                    let known = store.food.vegetable + store.food.meat + store.food.staple
                    let names = Set(known.map(\.name) + ProduceStorageAdvice.names)
                    let aliases = store.data.normalizer.aliases.filter { names.contains($0.value) }.map(\.key)
                    let scan = try await FridgePhotoRecognition.recognize(
                        input.data, knownNames: Array(names) + aliases)
                    try Task.checkCancellation()
                    selectedPhoto = nil
                    result = scan
                    if !FridgeVisionSheet.isAvailable { self.input = nil }
                } catch is CancellationError {
                    return
                } catch {
                    guard !Task.isCancelled else { return }
                    self.error = "这次识别没有成功，请拍清晰一些，或返回手动添加。"
                    self.selectedPhoto = nil
                    self.input = nil
                }
            }
            #if DEBUG
                .task {
                    // UI tests exercise the same Vision pipeline with a generated label image.
                    if !fixtureLoaded, ProcessInfo.processInfo.arguments.contains("-ui-testing"),
                        let data = Self.fixturePhoto
                    {
                        fixtureLoaded = true
                        input = ScanInput(data: data)
                    }
                }
            #endif
        }
    }

    private var batchSuggestion: some View {
        Form {
            Section {
                Text("本机还没确认单份食材。照片里有多种食材时，可以试试批量识图。")
            }
            Section {
                Button("按一份食材录入", action: showSingleItem)
                Button("换一张照片", action: resetPhoto)
            }
        }
        .bottomActionBar {
            Button {
                showingBatch = true
            } label: {
                Text("批量识图（会员）").frame(maxWidth: .infinity)
            }
            .prominentButtonStyle()
            .controlSize(.large)
            .accessibilityIdentifier("fridge.scan.batch")
        }
    }

    private func showSingleItem() {
        showingSingleItem = true
        showingBatch = false
    }

    private func resetPhoto() {
        showingBatch = false
        showingSingleItem = false
        selectedPhoto = nil
        input = nil
        result = nil
        error = nil
    }

    #if DEBUG
        private static var fixturePhoto: Data? {
            if let data = FridgeVisionSheet.testPhoto { return data }
            guard let encoded = ProcessInfo.processInfo.environment["COOK_FRIDGE_PHOTO_FIXTURE"],
                encoded.count <= 2 * 1024 * 1024
            else { return nil }
            return Data(base64Encoded: encoded)
        }
    #endif

    private func openCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            error = "这台设备暂时无法拍照，可以从照片选择。"
            return
        }
        Task { @MainActor in
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            if granted { showingCamera = true } else { cameraDenied = true }
        }
    }
}

#Preview("拍照录入") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        FridgePhotoSheet()
            .environment(store)
            .modelContainer(Persistence.makeContainer(inMemory: true))
    }
}

#Preview("蔬果建议 · 大字体与深色") {
    if let store = try? RecipeStore.loadBundled(inMemory: true) {
        NavigationStack {
            FridgePhotoReview(scan: FridgeLabelScan(text: "", names: ["草莓"]), onRetake: {})
                .navigationTitle("确认识别结果")
                .navigationBarTitleDisplayMode(.inline)
        }
        .environment(store)
        .modelContainer(Persistence.makeContainer(inMemory: true))
        .environment(\.dynamicTypeSize, .accessibility3)
        .preferredColorScheme(.dark)
    }
}

private struct FridgePhotoReview: View {
    @Environment(RecipeStore.self) private var store
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    let scan: FridgeLabelScan
    let onRetake: () -> Void
    let onBatch: (() -> Void)?
    @State private var name: String
    @State private var startDay = Date()
    @State private var cut = false
    @State private var expiresAt: Date
    @State private var basis: FridgeExpiryBasis
    @State private var hasDate: Bool
    @State private var hasProductionDate: Bool
    @State private var productionDay: Date
    @State private var saveError = false
    @State private var category = FridgeCategoryChoice.automatic

    init(scan: FridgeLabelScan, onRetake: @escaping () -> Void, onBatch: (() -> Void)? = nil) {
        self.scan = scan
        self.onRetake = onRetake
        self.onBatch = onBatch
        _name = State(initialValue: scan.names.count == 1 ? scan.names[0] : "")
        _expiresAt = State(initialValue: scan.expiryDay ?? Date())
        _basis = State(initialValue: scan.basis)
        _hasDate = State(initialValue: scan.expiryDay != nil)
        _hasProductionDate = State(initialValue: scan.productionDay != nil)
        _productionDay = State(initialValue: scan.productionDay ?? Date())
    }

    private var cleanName: String { store.data.normalizer.normalize([name])[0] }
    private var advice: ProduceStorageAdvice? { ProduceStorageAdvice.recommendation(for: cleanName, cut: cut) }
    private var isProduce: Bool {
        ProduceStorageAdvice.names.contains(cleanName) || store.food.vegetable.contains { $0.name == cleanName }
    }

    var body: some View {
        Form {
            Section("食材") {
                TextField("确认食材名称", text: $name)
                    .accessibilityIdentifier("fridge.scan.name")
                if scan.names.count > 1 {
                    ForEach(scan.names, id: \.self) { candidate in
                        Button("选用\(candidate)") { name = candidate }
                    }
                }
                Text("图像识别可能出错，请核对食材种类和包装原文。")
                    .font(.footnote).foregroundStyle(.secondary)
                if let onBatch {
                    Button("改用批量识图（会员）", action: onBatch)
                        .accessibilityIdentifier("fridge.scan.batch")
                }
            }
            Section {
                FridgeCategoryPicker(
                    selection: $category,
                    automaticTitle: cleanName.isEmpty ? nil : FridgeBuiltInCategory.automatic(for: cleanName, in: store.food).title)
            }
            Section {
                ForEach(scan.messages, id: \.self) { Text($0).font(.footnote).foregroundStyle(.secondary) }
                Toggle("记录生产日期", isOn: $hasProductionDate)
                if hasProductionDate {
                    DatePicker("生产日期", selection: $productionDay, in: ...Date(), displayedComponents: .date)
                }
                if !scan.dateCandidates.isEmpty {
                    DisclosureGroup("识别出的日期（点选为包装到期日）") {
                        ForEach(scan.dateCandidates, id: \.self) { day in
                            Button(day.formatted(date: .numeric, time: .omitted)) {
                                expiresAt = day
                                basis = .package
                                hasDate = true
                            }
                        }
                    }
                }
                Picker("日期依据", selection: $basis) {
                    ForEach(FridgeExpiryBasis.allCases.filter { $0 != .calculated || canCalculate }) { Text($0.title).tag($0) }
                }
                if hasDate {
                    DatePicker(basis.isSuggested ? "建议吃完日期" : "到期日", selection: editableExpiry, displayedComponents: .date)
                        .accessibilityIdentifier("fridge.scan.expiry")
                } else {
                    Button("手动设置日期") {
                        expiresAt = Date()
                        basis = .manual
                        hasDate = true
                    }
                }
            } header: {
                Text("核对日期")
            } footer: {
                Text("包装日期仅适用于标明的储存条件和未开封状态；开封后请按包装要求缩短时间。照片不能判断食物是否安全。")
            }
            Section {
                DatePicker(cut ? "切开日期" : "购买日期", selection: $startDay, in: ...Date(), displayedComponents: .date)
                if isProduce { Toggle("已经切开或去皮", isOn: $cut) }
                if let kind = FridgeProduceExample.Kind(rawValue: cleanName) {
                    FridgeProduceExample(kind, expanded: true)
                        .id(kind)
                }
                if let advice {
                    Text("建议 \(advice.days) 天内吃完。\(advice.condition)")
                    Link("查看储存建议来源", destination: advice.source)
                } else {
                    Text("还没有该食材的专属储存建议。可参考现有通用记录天数（\(store.data.shelfLife.days(for: cleanName)) 天），并自行调整。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Button("采用食用时间建议") { useSuggestion() }
                    .disabled(cleanName.isEmpty)
                    .accessibilityIdentifier("fridge.scan.useSuggestion")
            } header: {
                Text("按食材安排食用")
            } footer: {
                Text("建议日期用于安排食用，不能保证保鲜或食用安全；已有变质迹象时不要等到记录日期。")
            }
            if !scan.text.isEmpty {
                Section {
                    DisclosureGroup("核对识别原文") { Text(scan.text).textSelection(.enabled) }
                }
            }
            Section {
                FridgeReminderRows()
            } footer: {
                Text("开启后在记录日期前一天和当天提醒，同一天合为一条。")
            }
            Section { Button("重新拍照或选图", action: onRetake) }
        }
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("确认放进去", action: save)
                    .disabled(cleanName.isEmpty || cleanName.count > 50 || !hasDate)
                    .accessibilityIdentifier("fridge.scan.save")
            }
        }
        .onChange(of: name) { _, _ in
            cut = false
            if basis == .estimated { useSuggestion() }
        }
        .onChange(of: startDay) { _, _ in if basis == .estimated { useSuggestion() } }
        .onChange(of: cut) { _, _ in if basis == .estimated { useSuggestion() } }
        .onChange(of: basis) { _, value in
            if value == .estimated { useSuggestion() }
            if value == .calculated { updateCalculatedDate() }
        }
        .onChange(of: productionDay) { _, _ in if basis == .calculated { updateCalculatedDate() } }
        .onChange(of: hasProductionDate) { _, value in if !value, basis == .calculated { basis = .manual } }
        .onAppear { if !hasDate, !cleanName.isEmpty { useSuggestion() } }
        .alert("食材未能保存", isPresented: $saveError) {
            Button("好") {}
        } message: {
            Text("请检查设备的可用储存空间后重试，当前识别结果已保留。")
        }
    }

    private var canCalculate: Bool {
        hasProductionDate && FridgeLabelParser.calculatedExpiry(productionDay: productionDay, labelText: scan.text) != nil
    }

    private var editableExpiry: Binding<Date> {
        Binding(
            get: { expiresAt },
            set: { day in
                expiresAt = day
                if basis == .estimated || basis == .calculated { basis = .manual }
            })
    }

    private func updateCalculatedDate() {
        guard hasProductionDate,
            let day = FridgeLabelParser.calculatedExpiry(productionDay: productionDay, labelText: scan.text)
        else { return }
        expiresAt = day
        hasDate = true
    }

    private func useSuggestion() {
        guard !cleanName.isEmpty else { return }
        expiresAt =
            advice?.date(from: startDay) ?? Calendar.current.date(
                byAdding: .day, value: store.data.shelfLife.days(for: cleanName), to: Calendar.current.startOfDay(for: startDay))
            ?? startDay
        basis = .estimated
        hasDate = true
    }

    private func save() {
        guard !cleanName.isEmpty, cleanName.count <= 50, hasDate else { return }
        let item = FridgeItem(name: cleanName, addedAt: Date(), expiresAt: FridgeDay.stored(expiresAt))
        item.productionDate = hasProductionDate ? FridgeDay.stored(productionDay) : nil
        item.expiryBasisRaw = basis.rawValue
        item.categoryKey = category.storedKey
        if basis == .estimated {
            let starting = "\(startDay.formatted(date: .numeric, time: .omitted))\(cut ? "切开" : "购买")。"
            item.storageAdvice = starting + (advice.map { "\($0.condition) 建议 \($0.days) 天内吃完。" } ?? "通用天数估算，请自行核对。")
        }
        context.insert(item)
        do {
            try context.save()
            dismiss()
        } catch {
            context.delete(item)
            saveError = true
        }
    }
}

struct FridgeCamera: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss
    let onPhoto: (Data) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: UIImagePickerController, context _: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: FridgeCamera
        init(parent: FridgeCamera) { self.parent = parent }

        func imagePickerController(
            _ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.9) {
                parent.onPhoto(data)
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_: UIImagePickerController) { parent.dismiss() }
    }
}
