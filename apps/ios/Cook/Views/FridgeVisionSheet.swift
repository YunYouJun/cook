import SwiftData
import SwiftUI
import UIKit

struct FridgeVisionSheet: View {
    let sourcePhoto: Data
    let onUseOffline: () -> Void
    let onRetake: () -> Void
    let onSaved: () -> Void
    @Environment(RecipeStore.self) private var store
    @Environment(YunlefunAccountStore.self) private var accounts
    @Environment(CookProStore.self) private var pro
    @Environment(\.modelContext) private var context
    @State private var photo: FridgeVisionPhoto?
    @State private var provider: FridgeVisionProvider?
    @State private var upload: Upload?
    @State private var candidates: [FridgeVisionCandidate]?
    @State private var error: String?
    @State private var failureToast: FailureToast?
    @State private var access = Access.checking
    @State private var accessQueryID = UUID()
    @State private var verifiedSession: VerifiedSession?
    @State private var showingMembership = false
    @State private var showingPaywall = false

    private enum Access { case checking, signIn, signInAgain, member, membershipRequired, unavailable }

    private enum VerifiedSession: Equatable {
        case account(UUID)
        case subscription(String)
    }

    private struct Upload: Identifiable {
        let id = UUID()
        let provider: FridgeVisionProvider
    }

    private struct FailureToast: Identifiable {
        let id = UUID()
        let message: String
    }

    static var isAvailable: Bool { FridgeVisionService.configuredEndpoint != nil }

    // Both the in-memory photo and the no-network recognizer are restricted to explicit debug UI tests.
    static var testPhoto: Data? {
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
        content
            .overlay(alignment: .top) {
                if let failureToast {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.circle.fill")
                            .foregroundStyle(.orange)
                            .accessibilityHidden(true)
                        Text(failureToast.message)
                            .accessibilityIdentifier("fridge.vision.failureToast")
                    }
                    .font(.subheadline.weight(.medium))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(12)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    .shadow(color: .black.opacity(0.15), radius: 8, y: 3)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .allowsHitTesting(false)
                }
            }
            .navigationTitle(candidates?.isEmpty == false ? "确认食材" : "批量识图")
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(isPresented: $showingMembership) { MembershipView() }
            .sheet(isPresented: $showingPaywall) {
                NavigationStack {
                    CookProPaywallView(highlight: .cloudAI, showsDismissButton: true)
                }
            }
            .task(id: accessQueryID) {
                await checkAccess()
                await preparePhoto()
            }
            .task(id: upload?.id) { await recognize() }
            .task(id: failureToast?.id) {
                guard let id = failureToast?.id else { return }
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled, failureToast?.id == id else { return }
                failureToast = nil
            }
            .onChange(of: accounts.sessionRevision) { refreshAccess() }
            .onChange(of: subscriptionSession) { refreshAccess() }
            .onChange(of: pro.isOnlineAIAllowedInCurrentRegion) { refreshAccess() }
            .onChange(of: showingMembership) { _, showing in if !showing { refreshAccess() } }
            .onChange(of: showingPaywall) { _, showing in if !showing { refreshAccess() } }
    }

    @ViewBuilder
    private var content: some View {
        if access == .checking {
            checkingAccess
        } else if candidates?.isEmpty == false {
            form.bottomActionBar {
                Button(action: save) {
                    Text("加入冰箱（\(saveNames?.count ?? 0) 样）").frame(maxWidth: .infinity)
                }
                .prominentButtonStyle()
                .controlSize(.large)
                .disabled(saveNames?.isEmpty != false)
                .accessibilityIdentifier("fridge.vision.save")
            }
        } else if access == .member, photo != nil {
            form.bottomActionBar { uploadAction }
        } else {
            form
        }
    }

    private var checkingAccess: some View {
        VStack(spacing: 12) {
            ProgressView()
                .accessibilityHidden(true)
            Text("正在核对会员资格…")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            offlineAction
                .padding(.vertical, 16)
                .frame(maxWidth: .infinity)
        }
        .background(Color(uiColor: .systemGroupedBackground))
    }

    private var form: some View {
        Form {
            if let candidates {
                if candidates.isEmpty {
                    Section {
                        ContentUnavailableView("没有识别出食材", systemImage: "photo.badge.magnifyingglass",
                                               description: Text("试着拍清冰箱里能看见的食材，或手动添加。"))
                    }
                } else {
                    FridgeVisionReview(candidates: $candidates, names: saveNames)
                }
                Section { Button("换一张照片", action: onRetake) }
            } else if access == .member {
                if let photo {
                    preview(photo)
                } else {
                    Section {
                        if error == nil { ProgressView("正在准备照片…") }
                        Button("换一张照片", action: onRetake)
                    }
                }
            } else {
                accessPrompt
            }

            if failureToast == nil, let error {
                Section { Text(error).foregroundStyle(.red) }
            }
        }
    }

    private var usesLegacyMembershipUI: Bool {
        CookFeatures.membershipUIEnabled || !CookFeatures.proStoreEnabled || Self.testPhoto != nil
            || (!pro.isProStoreAvailableInCurrentRegion && !pro.isAISubscriptionAllowedInCurrentRegion)
    }

    private var offlineAction: some View {
        Button("返回本机识别", action: onUseOffline)
            .accessibilityIdentifier("fridge.vision.offline")
    }

    private var accessPrompt: some View {
        Section {
            switch access {
            case .signIn:
                NavigationLink("登录云乐坊账号") { YunlefunAccountView() }
            case .signInAgain:
                Text("请退出后重新登录，当前照片会保留。")
                NavigationLink("前往账号设置") { YunlefunAccountView() }
            case .membershipRequired:
                Button(usesLegacyMembershipUI ? "查看会员权益" : "订阅海外云端 AI") {
                    if usesLegacyMembershipUI {
                        showingMembership = true
                    } else {
                        showingPaywall = true
                    }
                }
                .accessibilityIdentifier("fridge.vision.membership")
            case .unavailable:
                Text("暂时无法核对服务资格，请稍后重试。")
                Button("重新核对资格", action: refreshAccess)
            case .checking, .member:
                EmptyView()
            }
            offlineAction
        } header: {
            Text(usesLegacyMembershipUI ? "云乐坊会员可用" : "云端 AI 订阅可用")
        } footer: {
            Text("一张照片识别多种食材，勾选后一次入库。离线拍照和手动添加仍可免费使用。")
        }
    }

    private func preview(_ photo: FridgeVisionPhoto) -> some View {
        Group {
            if let image = UIImage(data: photo.jpeg) {
                Section("所选照片") {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: 240)
                        .accessibilityLabel("所选冰箱照片")
                }
            }
            Section {
                Text("上传前会移除位置等信息。请避开人物、地址等私密内容。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                NavigationLink("隐私政策") { PrivacyPolicyView() }
                if upload == nil { Button("换一张照片", action: onRetake) }
            }
        }
    }

    @ViewBuilder
    private var uploadAction: some View {
        if upload != nil {
            ProgressView("正在识别食材…").frame(maxWidth: .infinity)
        } else if let provider {
            VStack(alignment: .leading, spacing: 8) {
                Text("照片将\(provider.photoProcessingDescription)。")
                    .font(.footnote)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("fridge.vision.recipient")
                Button {
                    clearFailure()
                    upload = Upload(provider: provider)
                } label: {
                    Text("同意并识别").frame(maxWidth: .infinity)
                }
                .prominentButtonStyle()
                .controlSize(.large)
                .accessibilityIdentifier("fridge.vision.upload")
            }
        } else {
            Button(action: refreshAccess) {
                Text("重新连接识图服务").frame(maxWidth: .infinity)
            }
            .prominentButtonStyle()
            .controlSize(.large)
        }
    }

    private var saveNames: [String]? {
        FridgeVisionCandidate.names(candidates ?? [], normalizer: store.data.normalizer)
    }

    private func checkAccess(rejecting rejected: YunlefunCredential? = nil, notice: String? = nil) async {
        guard !Task.isCancelled, access == .checking else { return }
        guard regionAllowsVision else { access = .unavailable; return }
        let query = accessQueryID
        if case let .subscription(jws)? = subscriptionSession, Self.testPhoto == nil {
            let session = VerifiedSession.subscription(jws)
            do {
                guard let endpoint = FridgeVisionService.configuredEndpoint else { throw FridgeVisionError.unavailable }
                let active = try await FridgeVisionService(endpoint: endpoint).memberProvider(authorization: .appleJWS(jws))
                guard !Task.isCancelled, accessQueryID == query, isCurrent(session) else { return }
                applyAccess(provider: active, session: session, notice: notice)
            } catch {
                guard !Task.isCancelled, accessQueryID == query, isCurrent(session) else { return }
                photo = nil
                switch error as? FridgeVisionError {
                case .loginRequired, .membershipRequired:
                    access = .membershipRequired
                default:
                    access = .unavailable
                    showFailure((error as? FridgeVisionError)?.errorDescription ?? "暂时无法核对订阅资格，请稍后重试。", inline: false)
                }
            }
            return
        }
        let revision = accounts.sessionRevision
        do {
            let active: FridgeVisionProvider?
            if Self.testPhoto != nil {
                let environment = ProcessInfo.processInfo.environment
                if environment["COOK_UI_TEST_FRIDGE_VISION_ACCESS_PENDING"] == "1" { return }
                if environment["COOK_UI_TEST_FRIDGE_VISION_NONMEMBER"] == "1" { active = nil }
                else { active = notice == nil ? .cloudBase : .deepSeek }
            } else {
                guard accounts.dataAccountID != nil else {
                    photo = nil
                    access = pro.isAISubscriptionAllowedInCurrentRegion && !CookFeatures.membershipUIEnabled
                        ? .membershipRequired : .signIn
                    return
                }
                guard let endpoint = FridgeVisionService.configuredEndpoint else { throw FridgeVisionError.unavailable }
                active = try await FridgeVisionService(endpoint: endpoint).memberProvider(using: accounts, rejecting: rejected)
            }
            guard !Task.isCancelled, accessQueryID == query, isCurrent(.account(revision)) else { return }
            applyAccess(provider: active, session: .account(revision), notice: notice)
        } catch {
            guard !Task.isCancelled, accessQueryID == query, isCurrent(.account(revision)) else { return }
            photo = nil
            switch (error as? FridgeVisionError, error as? YunlefunAccountError) {
            case (.loginRequired, _), (_, .expiredSession): access = accounts.account == nil ? .signIn : .signInAgain
            case (.membershipRequired, _): access = .membershipRequired
            default:
                access = .unavailable
                showFailure((error as? FridgeVisionError)?.errorDescription ?? "暂时无法核对会员资格，请稍后重试。", inline: false)
            }
        }
    }

    private var regionAllowsVision: Bool {
        Self.testPhoto != nil || pro.isOnlineAIAllowedInCurrentRegion
    }

    private var subscriptionSession: VerifiedSession? {
        guard case let .appleJWS(jws)? = pro.appleAIAuthorization else { return nil }
        return .subscription(jws)
    }

    private func isCurrent(_ session: VerifiedSession) -> Bool {
        guard regionAllowsVision else { return false }
        switch session {
        case let .account(revision): return accounts.sessionRevision == revision
        case .subscription: return subscriptionSession == session
        }
    }

    private func applyAccess(provider active: FridgeVisionProvider?, session: VerifiedSession, notice: String?) {
        provider = active
        verifiedSession = active == nil ? nil : session
        if active == nil { photo = nil }
        access = active == nil ? .membershipRequired : .member
        if active != nil, let notice { showFailure(notice) }
    }

    private func preparePhoto() async {
        guard !Task.isCancelled, access == .member, photo == nil, candidates == nil else { return }
        let query = accessQueryID
        do {
            let prepared = try await FridgeVisionPhoto.prepare(sourcePhoto)
            try Task.checkCancellation()
            guard accessQueryID == query, access == .member else { return }
            photo = prepared
        } catch {
            guard !Task.isCancelled, accessQueryID == query else { return }
            showFailure(FridgeVisionError.unreadablePhoto.localizedDescription)
        }
    }

    private func recognize() async {
        guard let upload else { return }
        var submittedCredential: YunlefunCredential?
        defer { if self.upload?.id == upload.id { self.upload = nil } }
        guard access == .member, let photo, let session = verifiedSession, isCurrent(session) else { return }
        do {
            let names: [String]
            if Self.testPhoto != nil {
                switch ProcessInfo.processInfo.environment["COOK_UI_TEST_FRIDGE_VISION_RESULT"] {
                case "timeout": throw FridgeVisionError.timedOut
                case "empty": names = []
                case "rejected" where upload.provider == .cloudBase: throw FridgeVisionError.loginRequired
                default: names = ["西红柿", "鸡蛋", "牛奶"]
                }
            } else {
                guard let endpoint = FridgeVisionService.configuredEndpoint else { throw FridgeVisionError.unavailable }
                let authorization: CookAIAuthorization
                switch session {
                case let .subscription(jws):
                    authorization = .appleJWS(jws)
                case .account:
                    let credential = try await accounts.credentialForAIRequest()
                    guard isCurrent(session), accounts.account?.id == credential.subject else { throw CancellationError() }
                    submittedCredential = credential
                    authorization = .yunlefunBearer(credential)
                }
                names = try await FridgeVisionService(endpoint: endpoint).recognize(
                    photo, provider: upload.provider, authorization: authorization
                )
            }
            try Task.checkCancellation()
            guard isCurrent(session), self.upload?.id == upload.id else { return }
            let normalized = FridgeVisionCandidate.names(names.map { FridgeVisionCandidate(name: $0) }, normalizer: store.data.normalizer)
            guard let normalized else { throw FridgeVisionError.invalidResponse }
            candidates = normalized.map { FridgeVisionCandidate(name: $0) }
            self.photo = nil
            if normalized.isEmpty {
                showFailure("没有识别出食材，请换一张照片。", inline: false)
            }
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled, isCurrent(session), self.upload?.id == upload.id else { return }
            switch (error as? FridgeVisionError, error as? YunlefunAccountError) {
            case (.membershipRequired, _):
                reset()
                access = .membershipRequired
                showFailure(FridgeVisionError.membershipRequired.localizedDescription, inline: false)
            case (.loginRequired, _) where submittedCredential != nil || Self.testPhoto != nil:
                await recheckConsent(rejecting: submittedCredential,
                                     notice: "已重新核对登录状态，请确认照片接收方后再识别。")
            case (.loginRequired, _) where session == subscriptionSession:
                reset()
                access = .membershipRequired
                showFailure(FridgeVisionError.membershipRequired.localizedDescription, inline: false)
            case (.loginRequired, _), (_, .expiredSession):
                reset()
                access = accounts.account == nil ? .signIn : .signInAgain
                showFailure(FridgeVisionError.loginRequired.localizedDescription, inline: false)
            case (.providerChanged, _):
                await recheckConsent(notice: FridgeVisionError.providerChanged.localizedDescription)
            default:
                showFailure((error as? FridgeVisionError)?.errorDescription ?? FridgeVisionError.unavailable.localizedDescription)
            }
        }
    }

    private func recheckConsent(rejecting credential: YunlefunCredential? = nil, notice: String) async {
        provider = nil
        verifiedSession = nil
        access = .checking
        await checkAccess(rejecting: credential, notice: notice)
    }

    private func save() {
        guard let session = verifiedSession, isCurrent(session), let names = saveNames, !names.isEmpty else { return }
        do {
            try context.addEstimatedFridgeItems(names, shelfLife: store.data.shelfLife, limit: pro.fridgeItemLimit)
            onSaved()
        } catch let quota as CookFeatureQuotaError {
            showFailure(quota.localizedDescription)
        } catch {
            showFailure("没有保存成功，请重试。")
        }
    }

    private func showFailure(_ message: String, inline: Bool = true) {
        error = inline ? message : nil
        failureToast = FailureToast(message: message)
        AccessibilityNotification.Announcement(message).post()
    }

    private func clearFailure() {
        error = nil
        failureToast = nil
    }

    private func reset() {
        photo = nil
        provider = nil
        verifiedSession = nil
        upload = nil
        candidates = nil
        clearFailure()
    }

    private func refreshAccess() {
        reset()
        access = .checking
        accessQueryID = UUID()
    }
}

private struct FridgeVisionReview: View {
    @Binding var candidates: [FridgeVisionCandidate]?
    let names: [String]?

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
                if names == nil {
                    Text("请修正勾选的食材名称，每项填写一个不超过 30 字的名称。")
                } else {
                    Text("每种记一份，日期按食用建议估算，入库后可调整。")
                }
            }
        }
    }
}
