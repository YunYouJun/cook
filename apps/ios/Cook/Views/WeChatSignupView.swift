import SwiftUI
import UIKit

struct WeChatSignupLink: View {
    @Environment(YunlefunAccountStore.self) private var accounts

    var body: some View {
        if CookFeatures.weChatSignupEnabled, accounts.canUseWeChat {
            NavigationLink {
                WeChatSignupView()
            } label: {
                Label("使用微信注册", systemImage: "person.badge.plus")
            }
            .disabled(accounts.isBusy)
            .accessibilityIdentifier("account.signUpWithWeChat")
        }
    }
}

private struct WeChatSignupView: View {
    private enum Step {
        case phone
        case sms(String)
        case confirm(String)
        case existing
        case accountCreated
    }

    @Environment(YunlefunAccountStore.self) private var accounts
    @Environment(\.dismiss) private var dismiss
    @State private var deviceID = UUID().uuidString
    @State private var step: Step = .phone
    @State private var phone = ""
    @State private var otp = ""
    @State private var captcha: WeChatSignupCaptcha?
    @State private var captchaAnswer = ""
    @State private var error: String?
    @State private var retrySMSAt = Date.distantPast
    @State private var work: Task<Void, Never>?
    @State private var registering = false
    private let service = YunlefunAccountService()

    var body: some View {
        Form {
            switch step {
            case .phone:
                Section {
                    TextField("中国大陆手机号", text: $phone)
                        .keyboardType(.phonePad)
                        .textContentType(.telephoneNumber)
                        .onChange(of: phone) { _, value in phone = digits(value, limit: 11) }
                        .accessibilityIdentifier("wechatSignup.phone")
                    if let captcha {
                        if let image = captchaImage(captcha.image) {
                            Image(uiImage: image)
                                .accessibilityLabel("图片验证码")
                        }
                        TextField("输入图片验证码", text: $captchaAnswer)
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                        Button("换一张图片") { run(loadCaptcha) }
                    }
                    Button("发送短信验证码") { run(sendSMS) }
                        .disabled(phone.range(of: "^1[3-9][0-9]{9}$", options: .regularExpression) == nil
                            || (captcha != nil && !(4...6).contains(captchaAnswer.count)))
                } footer: {
                    Text("先验证手机号。如果这个号码已有云乐坊账号，请登录原账号，再绑定微信。")
                }
            case .sms(let challenge):
                Section {
                    TextField("短信验证码", text: $otp)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .onChange(of: otp) { _, value in otp = digits(value, limit: 6) }
                        .accessibilityIdentifier("wechatSignup.otp")
                    Button("验证手机号") {
                        run {
                            let next = try await service.verifyWeChatSignupSMS(challenge: challenge, otp: otp, deviceID: deviceID)
                            try Task.checkCancellation()
                            otp = ""
                            switch next {
                            case .existingAccount: step = .existing
                            case .confirm(let proof): step = .confirm(proof)
                            }
                        }
                    }
                    .disabled(otp.count != 6)
                    TimelineView(.periodic(from: .now, by: 1)) { context in
                        let seconds = max(0, Int(ceil(retrySMSAt.timeIntervalSince(context.date))))
                        Button(seconds > 0 ? "重新发送（\(seconds) 秒）" : "重新发送") { run(sendSMS) }
                            .disabled(seconds > 0)
                    }
                    Button("修改手机号", action: reset)
                } footer: {
                    Text("短信已发送至 +86 \(phone)。")
                }
            case .confirm(let proof):
                Section {
                    Button("创建账号并授权微信") {
                        registering = true
                        run {
                            defer { registering = false }
                            try await accounts.registerWithWeChat(proof: proof, deviceID: deviceID)
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

    private func run(_ operation: @escaping @MainActor () async throws -> Void) {
        guard work == nil else { return }
        error = nil
        work = Task {
            defer { work = nil }
            do { try await operation() }
            catch is CancellationError {}
            catch WeChatSignupError.existingAccount { step = .existing }
            catch WeChatSignupError.accountCreated { step = .accountCreated }
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

    private func sendSMS() async throws {
        do {
            let answer = captcha.map { ($0.challenge, captchaAnswer) }
            let challenge = try await service.sendWeChatSignupSMS(phone: phone, captcha: answer, deviceID: deviceID)
            try Task.checkCancellation()
            captcha = nil
            captchaAnswer = ""
            otp = ""
            retrySMSAt = .now.addingTimeInterval(60)
            step = .sms(challenge)
        } catch WeChatSignupError.captchaRequired {
            step = .phone
            try await loadCaptcha()
        } catch WeChatSignupError.captchaInvalid {
            try await loadCaptcha()
            error = WeChatSignupError.captchaInvalid.errorDescription
        }
    }

    private func loadCaptcha() async throws {
        captcha = try await service.weChatSignupCaptcha(deviceID: deviceID)
        captchaAnswer = ""
    }

    private func reset() {
        step = .phone
        otp = ""
        captcha = nil
        captchaAnswer = ""
        error = nil
        deviceID = UUID().uuidString
    }

    private func digits(_ input: String, limit: Int) -> String {
        String(input.filter { $0.isASCII && $0.isNumber }.prefix(limit))
    }

    private func captchaImage(_ uri: String) -> UIImage? {
        let parts = uri.split(separator: ",", maxSplits: 1)
        guard parts.count == 2, ["data:image/gif;base64", "data:image/png;base64", "data:image/jpeg;base64"].contains(String(parts[0])),
              parts[1].count <= 200_000, let data = Data(base64Encoded: String(parts[1])) else { return nil }
        return UIImage(data: data)
    }
}
