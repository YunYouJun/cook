import SwiftUI
import UIKit

struct WeChatSignupLink: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        if CookFeatures.weChatSignupEnabled, accounts.canUseWeChat {
            NavigationLink {
                WeChatSignupView()
            } label: {
                SettingsRowLabel("使用微信注册", symbol: "person.badge.plus", color: .green)
            }
            .disabled(accounts.isBusy)
            .accessibilityIdentifier("account.signUpWithWeChat")
        }
    }
}

private struct WeChatSignupView: View {
    @Environment(YunlefunAccountStore.self) private var accounts
    @Environment(\.dismiss) private var dismiss
    @State private var form = WeChatSignupForm(service: YunlefunAccountService())
    @State private var error: String?
    @State private var work: Task<Void, Never>?
    @State private var registering = false

    var body: some View {
        Form {
            switch form.step {
            case .phone:
                Section {
                    TextField("中国大陆手机号", text: Binding(get: { form.phone }, set: form.changePhone))
                        .keyboardType(.phonePad)
                        .textContentType(.telephoneNumber)
                        .accessibilityIdentifier("wechatSignup.phone")
                    captchaFields
                    Button("发送短信验证码") { run(form.sendSMS) }
                        .disabled(!form.canSubmitPhone || !form.canSubmitCaptcha)
                } footer: {
                    Text("先验证手机号。如果这个号码已有云乐坊账号，请登录原账号，再绑定微信。")
                }
            case .sms(let challenge):
                Section {
                    TextField("短信验证码", text: Binding(get: { form.otp }, set: form.changeOTP))
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .accessibilityIdentifier("wechatSignup.otp")
                    Button("验证手机号") {
                        run { try await form.verifySMS(challenge: challenge) }
                    }
                    .disabled(!form.canVerifySMS)
                    captchaFields
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let seconds = max(0, Int(ceil(form.retrySMSAt.timeIntervalSince(context.date))))
                        Button(seconds > 0 ? "重新发送（\(seconds) 秒）" : "重新发送") { run(form.sendSMS) }
                            .disabled(seconds > 0 || !form.canSubmitCaptcha)
                    }
                    Button("修改手机号", action: reset)
                } footer: {
                    Text("短信已发送至 +86 \(form.phone)。")
                }
            case .confirm(let proof):
                Section {
                    Button("创建账号并授权微信") {
                        run(registering: true) {
                            try await accounts.registerWithWeChat(proof: proof, deviceID: form.deviceID)
                        }
                    }
                    Link("微信 OpenSDK 个人信息处理规则",
                         destination: URL(string: "https://weixin.qq.com/cgi-bin/readtemplate?t=opensdk_agreement&lang=zh_CN")!)
                    Button("修改手机号", action: reset)
                } footer: {
                    Text("手机号已验证，未找到关联的云乐坊账号。继续后将打开微信；授权成功才会创建并绑定账号。")
                }
            case .existing:
                Section {
                    Button("返回登录原账号") { dismiss() }
                    Button("换个手机号", action: reset)
                } footer: {
                    Text("此手机号已有云乐坊账号。用原方式登录后，在「我的 → 云乐坊账号」绑定微信。")
                }
            case .accountCreated:
                Section {
                    Button("返回登录") { dismiss() }
                } footer: {
                    Text("账号已创建。请用已验证的手机号登录，再到账号页检查微信绑定。")
                }
            case .unconfirmed:
                Section {
                    Button("返回登录") { dismiss() }
                    Button("重新验证手机号", action: reset)
                } footer: {
                    Text(WeChatSignupError.unconfirmed.localizedDescription)
                }
            }
            if let error {
                Section { Text(error).foregroundStyle(.red) }
            }
            if work != nil {
                Section {
                    ProgressView(registering ? "等待微信授权或正在创建账号" : "正在验证")
                }
            }
        }
        .disabled(work != nil || accounts.isBusy)
        .navigationTitle("使用微信注册")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(registering)
        .onChange(of: accounts.account?.id) { _, id in if id != nil { dismiss() } }
        .onDisappear { work?.cancel() }
    }

    @ViewBuilder
    private var captchaFields: some View {
        if let captcha = form.captcha {
            if let image = captchaImage(captcha.image) {
                Image(uiImage: image)
                    .accessibilityLabel("图片验证码")
            }
            TextField("输入图片验证码", text: $form.captchaAnswer)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            Button("换一张图片") { run(form.loadCaptcha) }
        }
    }

    private func run(registering: Bool = false, _ operation: @escaping @MainActor () async throws -> Void) {
        guard work == nil else { return }
        error = nil
        if registering { self.registering = true }
        work = Task {
            defer {
                if registering { self.registering = false }
                work = nil
            }
            do { try await operation() }
            catch is CancellationError {}
            catch WeChatSignupError.existingAccount { form.step = .existing }
            catch WeChatSignupError.accountCreated { form.step = .accountCreated }
            catch WeChatSignupError.unconfirmed { form.step = .unconfirmed }
            catch WeChatSignupError.restart {
                reset()
                error = WeChatSignupError.restart.errorDescription
            } catch let failure as LocalizedError {
                error = failure.errorDescription ?? "暂时无法完成注册，请稍后重试。"
            } catch {
                self.error = "暂时无法完成注册，请稍后重试。"
            }
        }
    }

    private func reset() {
        form.reset()
        error = nil
    }

    private func captchaImage(_ uri: String) -> UIImage? {
        let parts = uri.split(separator: ",", maxSplits: 1)
        guard parts.count == 2, ["data:image/gif;base64", "data:image/png;base64", "data:image/jpeg;base64"].contains(String(parts[0])),
              parts[1].count <= 200_000, let data = Data(base64Encoded: String(parts[1])) else { return nil }
        return UIImage(data: data)
    }
}
