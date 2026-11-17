import AVFoundation
import PhotosUI
import SwiftData
import SwiftUI
import UIKit

struct FridgeVisionSheet: View {
    let onUseOffline: () -> Void
    @Environment(RecipeStore.self) private var store
    @Environment(YunlefunAccountStore.self) private var accounts
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State private var selection: PhotosPickerItem?
    @State private var photoInput: PhotoInput?
    @State private var photo: FridgeVisionPhoto?
    @State private var loadingPhoto = false
    @State private var cameraOpen = false
    @State private var cameraData: Data?
    @State private var cameraDenied = false
    @State private var provider: FridgeVisionProvider?
    @State private var providerQueryID: UUID?
    @State private var upload: Upload?
    @State private var candidates: [FridgeVisionCandidate]?
    @State private var error: String?
    @State private var access = Access.checking
    @State private var accessQueryID = UUID()
    @State private var showingMembership = false

    private enum Access { case checking, signIn, member, membershipRequired, unavailable }

    private struct PhotoInput: Identifiable {
        let id = UUID()
        let data: Data
    }

    private struct Upload: Identifiable {
        let id = UUID()
        let provider: FridgeVisionProvider
    }

    static var isAvailable: Bool { FridgeVisionService.configuredEndpoint != nil || testPhoto != nil }

    // Both the in-memory photo and the no-network recognizer are restricted to explicit debug UI tests.
    private static var testPhoto: Data? {
        #if DEBUG
            let process = ProcessInfo.processInfo
            if process.arguments.contains("-ui-testing"), let value = process.environment["COOK_UI_TEST_FRIDGE_VISION_JPEG"],
               value.utf8.count <= 100_000 {
                return Data(base64Encoded: value)
            }
        #endif
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                if let candidates {
                    if candidates.isEmpty {
                        Section {
                            ContentUnavailableView("没有识别出食材", systemImage: "photo.badge.magnifyingglass",
                                                   description: Text("试着拍清冰箱里能看见的食材，或手动添加。"))
                        }
                    } else {
                        FridgeVisionReview(candidates: $candidates, names: saveNames, action: save)
                    }
                    Section { Button("换一张照片", action: refreshAccess) }
                } else if access == .member {
                    if let photo {
                        preview(photo)
                    } else {
                        photoPicker
                    }
                } else {
                    accessPrompt
                }

                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle(candidates == nil ? "AI 识图" : "确认食材")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(isPresented: $showingMembership) { MembershipView() }
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .fullScreenCover(isPresented: $cameraOpen, onDismiss: acceptCameraPhoto) {
                FridgeCamera { cameraData = $0 }.ignoresSafeArea()
            }
            .alert("相机没有打开", isPresented: $cameraDenied) {
                Button("取消", role: .cancel) {}
                Button("去设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
            } message: {
                Text("允许相机权限后可以拍照，也可以直接从照片选择。")
            }
            .task(id: accessQueryID) { await checkAccess() }
            .task(id: selection) { await loadSelection() }
            .task(id: photoInput?.id) { await preparePhoto() }
            .task(id: providerQueryID) { await loadProvider() }
            .task(id: upload?.id) { await recognize() }
            .onChange(of: accounts.sessionRevision) { refreshAccess() }
            .onChange(of: showingMembership) { _, showing in if !showing { refreshAccess() } }
        }
    }

    private var accessPrompt: some View {
        Section {
            switch access {
            case .checking:
                ProgressView("正在核对会员资格…")
            case .signIn:
                NavigationLink("登录云乐坊账号") { YunlefunAccountView() }
            case .membershipRequired:
                Button("查看会员权益") { showingMembership = true }
                    .accessibilityIdentifier("fridge.vision.membership")
            case .unavailable:
                Text("暂时无法核对会员资格，请稍后重试。")
                Button("重新核对会员资格", action: refreshAccess)
            case .member:
                EmptyView()
            }
            Button("继续使用离线拍照", action: onUseOffline)
                .accessibilityIdentifier("fridge.vision.offline")
        } header: {
            Text("云乐坊会员可用")
        } footer: {
            Text("一张照片识别多种食材，勾选后一次入库。离线拍照和手动添加仍可免费使用。")
        }
    }

    private var photoPicker: some View {
        Section {
            Button("拍一张照片", systemImage: "camera", action: openCamera)
            PhotosPicker(selection: $selection, matching: .images, preferredItemEncoding: .compatible) {
                Label("从照片选择", systemImage: "photo")
            }
            if loadingPhoto || photoInput != nil { ProgressView("正在准备照片…") }
        } header: {
            Text("拍清冰箱里看得见的食材")
        } footer: {
            Text("你可以先预览照片。只有同意后才上传，识别出的食材可逐项核对。")
        }
        .disabled(loadingPhoto || photoInput != nil)
    }

    private func preview(_ photo: FridgeVisionPhoto) -> some View {
        Group {
            if let image = UIImage(data: photo.jpeg) {
                Section("将要上传的照片") {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: 240)
                        .accessibilityLabel("所选冰箱照片")
                }
            }
            Section {
                if let provider {
                    Text("同意后，这张去除位置等元数据的照片会通过云乐坊（腾讯云 CloudBase）发送给\(provider.name)，用于识别食材。云食册不会把照片保存到冰箱记录。请避开人物、地址或其他私密内容。")
                        .accessibilityIdentifier("fridge.vision.recipient")
                    NavigationLink("隐私政策") { PrivacyPolicyView() }
                }
                if upload != nil {
                    ProgressView("正在识别照片里的食材…")
                } else if providerQueryID != nil {
                    ProgressView("正在确认照片接收方…")
                } else if let provider {
                    Button("同意并上传识别") {
                        error = nil
                        upload = Upload(provider: provider)
                    }
                    .fontWeight(.semibold)
                    .accessibilityIdentifier("fridge.vision.upload")
                } else {
                    Button("重新连接识图服务") {
                        error = nil
                        providerQueryID = UUID()
                    }
                }
                if upload == nil { Button("换一张照片", action: refreshAccess) }
            }
        }
    }

    private var saveNames: [String]? {
        FridgeVisionCandidate.names(candidates ?? [], normalizer: store.data.normalizer)
    }

    private func checkAccess() async {
        guard !Task.isCancelled else { return }
        #if DEBUG
            if let testPhoto = Self.testPhoto {
                if ProcessInfo.processInfo.environment["COOK_UI_TEST_FRIDGE_VISION_NONMEMBER"] == "1" {
                    access = .membershipRequired
                } else {
                    access = .member
                    photoInput = PhotoInput(data: testPhoto)
                }
                return
            }
        #endif
        guard accounts.account != nil else { access = .signIn; return }
        let query = accessQueryID
        let revision = accounts.sessionRevision
        do {
            guard let endpoint = FridgeVisionService.configuredEndpoint else { throw FridgeVisionError.unavailable }
            let credential = try await accounts.credentialForAIRequest()
            guard accounts.sessionRevision == revision, accounts.account?.id == credential.subject else { return }
            let eligible = try await FridgeVisionService(endpoint: endpoint).hasMembership(credential: credential)
            guard !Task.isCancelled, accessQueryID == query, accounts.sessionRevision == revision else { return }
            access = eligible ? .member : .membershipRequired
        } catch {
            guard !Task.isCancelled, accessQueryID == query, accounts.sessionRevision == revision else { return }
            switch (error as? FridgeVisionError, error as? YunlefunAccountError) {
            case (.loginRequired, _), (_, .expiredSession): access = .signIn
            default: access = .unavailable
            }
        }
    }

    private func loadSelection() async {
        guard access == .member, let selection else { return }
        loadingPhoto = true
        error = nil
        defer { if self.selection == selection { loadingPhoto = false } }
        do {
            guard let data = try await selection.loadTransferable(type: Data.self) else { throw FridgeVisionError.unreadablePhoto }
            try Task.checkCancellation()
            photoInput = PhotoInput(data: data)
        } catch {
            if !Task.isCancelled {
                self.error = FridgeVisionError.unreadablePhoto.localizedDescription
                loadingPhoto = false
                self.selection = nil
            }
        }
    }

    private func preparePhoto() async {
        guard access == .member, let photoInput else { return }
        defer { if self.photoInput?.id == photoInput.id { self.photoInput = nil } }
        do {
            let prepared = try await FridgeVisionPhoto.prepare(photoInput.data)
            try Task.checkCancellation()
            photo = prepared
            providerQueryID = UUID()
        } catch {
            if !Task.isCancelled { self.error = FridgeVisionError.unreadablePhoto.localizedDescription }
        }
        if !Task.isCancelled {
            selection = nil
            loadingPhoto = false
        }
    }

    private func loadProvider() async {
        guard let providerQueryID, let photo else { return }
        defer { if self.providerQueryID == providerQueryID { self.providerQueryID = nil } }
        do {
            let active: FridgeVisionProvider
            if Self.testPhoto != nil {
                active = .deepSeek
            } else {
                guard let endpoint = FridgeVisionService.configuredEndpoint else { throw FridgeVisionError.unavailable }
                active = try await FridgeVisionService(endpoint: endpoint).activeProvider()
            }
            try Task.checkCancellation()
            guard self.providerQueryID == providerQueryID, self.photo?.id == photo.id else { return }
            provider = active
        } catch {
            guard !Task.isCancelled, self.providerQueryID == providerQueryID, self.photo?.id == photo.id else { return }
            self.error = (error as? LocalizedError)?.errorDescription ?? FridgeVisionError.unavailable.localizedDescription
        }
    }

    private func recognize() async {
        guard access == .member, let upload, let photo else { return }
        let revision = accounts.sessionRevision
        defer { if self.upload?.id == upload.id { self.upload = nil } }
        do {
            let names: [String]
            if Self.testPhoto != nil {
                names = ["西红柿", "鸡蛋", "牛奶"]
            } else {
                guard let endpoint = FridgeVisionService.configuredEndpoint else { throw FridgeVisionError.unavailable }
                let credential = try await accounts.credentialForAIRequest()
                guard accounts.sessionRevision == revision, accounts.account?.id == credential.subject else { throw CancellationError() }
                names = try await FridgeVisionService(endpoint: endpoint).recognize(photo, provider: upload.provider, credential: credential)
            }
            try Task.checkCancellation()
            guard accounts.sessionRevision == revision, self.upload?.id == upload.id else { return }
            let normalized = FridgeVisionCandidate.names(names.map { FridgeVisionCandidate(name: $0) }, normalizer: store.data.normalizer)
            guard let normalized else { throw FridgeVisionError.invalidResponse }
            candidates = normalized.map { FridgeVisionCandidate(name: $0) }
            self.photo = nil
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled, accounts.sessionRevision == revision, self.upload?.id == upload.id else { return }
            switch error as? FridgeVisionError {
            case .membershipRequired:
                reset()
                access = .membershipRequired
            case .loginRequired:
                reset()
                access = .signIn
            case .providerChanged:
                self.error = FridgeVisionError.providerChanged.localizedDescription
                provider = nil
                providerQueryID = UUID()
            default:
                self.error = (error as? LocalizedError)?.errorDescription ?? FridgeVisionError.unavailable.localizedDescription
            }
        }
    }

    private func save() {
        guard let names = saveNames, !names.isEmpty else { return }
        do {
            try context.addEstimatedFridgeItems(names, shelfLife: store.data.shelfLife)
            dismiss()
        } catch {
            self.error = "没有保存成功，请重试。"
        }
    }

    private func reset() {
        selection = nil
        photoInput = nil
        photo = nil
        loadingPhoto = false
        cameraData = nil
        cameraOpen = false
        provider = nil
        providerQueryID = nil
        upload = nil
        candidates = nil
        error = nil
    }

    private func refreshAccess() {
        reset()
        access = .checking
        accessQueryID = UUID()
    }

    private func acceptCameraPhoto() {
        guard access == .member, let cameraData else { return }
        photoInput = PhotoInput(data: cameraData)
        self.cameraData = nil
    }

    private func openCamera() {
        guard access == .member else { return }
        error = nil
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            error = "这台设备暂时无法拍照，可以从照片选择。"
            return
        }
        Task {
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            guard access == .member else { return }
            if granted { cameraOpen = true } else { cameraDenied = true }
        }
    }
}

private struct FridgeVisionReview: View {
    @Binding var candidates: [FridgeVisionCandidate]?
    let names: [String]?
    let action: () -> Void

    var body: some View {
        if let rows = Binding($candidates) {
            Section {
                ForEach(rows) { $candidate in
                    HStack(spacing: 12) {
                        Button {
                            candidate.included.toggle()
                        } label: {
                            Image(systemName: candidate.included ? "checkmark.circle.fill" : "circle")
                                .font(.title3)
                                .frame(minWidth: 44, minHeight: 44)
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(candidate.included ? "取消勾选\(candidate.name)" : "勾选\(candidate.name)")
                        .accessibilityAddTraits(candidate.included ? .isSelected : [])
                        TextField("食材名称", text: $candidate.name)
                            .accessibilityLabel("食材名称：\(candidate.name)")
                            .accessibilityIdentifier("fridge.vision.name.\(candidate.id)")
                    }
                }
            } header: {
                Text("核对名称，取消勾选识别错的食材")
            } footer: {
                Text("每种食材记一份。日期使用冰箱的食用建议或通用估算，可在入库后调整。包装日期请以包装为准。")
            }
            Section {
                Button("加入冰箱（\(names?.count ?? 0) 样）", action: action)
                    .fontWeight(.semibold)
                    .disabled(names?.isEmpty != false)
                    .accessibilityIdentifier("fridge.vision.save")
            } footer: {
                if names == nil { Text("请修正勾选的食材名称，每项填写一个不超过 30 字的名称。") }
            }
        }
    }
}
