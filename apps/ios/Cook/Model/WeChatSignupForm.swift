import Foundation
import Observation

@MainActor
@Observable
final class WeChatSignupForm {
    enum Step {
        case phone
        case sms(String)
        case confirm(String)
        case existing
        case accountCreated
        case unconfirmed
    }

    var step: Step = .phone
    var phone = ""
    var otp = ""
    var captchaAnswer = ""
    private(set) var deviceID = UUID().uuidString
    private(set) var captcha: WeChatSignupCaptcha?
    private(set) var retrySMSAt = Date.distantPast

    private static let formattingSpaces: Set<Character> = [" ", "\u{00a0}", "\u{202f}"]
    private let service: YunlefunAccountService

    init(service: YunlefunAccountService) {
        self.service = service
    }

    var canSubmitCaptcha: Bool {
        captcha == nil || (4...6).contains(captchaAnswer.count)
    }

    var canSubmitPhone: Bool { Self.isNationalPhone(phone) }
    var canVerifySMS: Bool { Self.isSMSCode(otp) }

    func changePhone(_ value: String) {
        let input = String(value.prefix(64))
        let compact = String(input.filter { !Self.formattingSpaces.contains($0) && !"-()".contains($0) })
        let national: String
        if compact.hasPrefix("+86") { national = String(compact.dropFirst(3)) }
        else if compact.hasPrefix("86"), compact.utf8.count == 13 { national = String(compact.dropFirst(2)) }
        else { national = compact }
        phone = input == value && Self.isNationalPhone(national) ? national : input
    }

    func changeOTP(_ value: String) {
        let input = String(value.prefix(32))
        let compact = String(input.filter { !Self.formattingSpaces.contains($0) })
        otp = input == value && Self.isSMSCode(compact) ? compact : input
    }

    private static func isNationalPhone(_ value: String) -> Bool {
        value.utf8.count == 11 && value.range(of: "^1[3-9][0-9]{9}$", options: .regularExpression) != nil
    }

    private static func isSMSCode(_ value: String) -> Bool {
        value.utf8.count == 6 && value.utf8.allSatisfy { (48...57).contains($0) }
    }

    func sendSMS() async throws {
        guard canSubmitPhone else { throw YunlefunAccountError.invalidResponse }
        do {
            let answer = captcha.map { ($0.challenge, captchaAnswer) }
            let challenge = try await service.sendWeChatSignupSMS(phone: phone, captcha: answer, deviceID: deviceID)
            try Task.checkCancellation()
            clearCaptcha()
            otp = ""
            retrySMSAt = .now.addingTimeInterval(60)
            step = .sms(challenge)
        } catch WeChatSignupError.captchaRequired {
            try await loadCaptcha()
        } catch WeChatSignupError.captchaInvalid {
            try await loadCaptcha()
            throw WeChatSignupError.captchaInvalid
        }
    }

    func verifySMS(challenge: String) async throws {
        guard canVerifySMS else { throw WeChatSignupError.invalidOTP }
        let next = try await service.verifyWeChatSignupSMS(challenge: challenge, otp: otp, deviceID: deviceID)
        try Task.checkCancellation()
        otp = ""
        clearCaptcha()
        switch next {
        case .existingAccount: step = .existing
        case .confirm(let proof): step = .confirm(proof)
        }
    }

    func loadCaptcha() async throws {
        try Task.checkCancellation()
        captchaAnswer = ""
        let next = try await service.weChatSignupCaptcha(deviceID: deviceID)
        try Task.checkCancellation()
        captcha = next
    }

    func reset() {
        step = .phone
        otp = ""
        clearCaptcha()
        deviceID = UUID().uuidString
        retrySMSAt = .distantPast
    }

    private func clearCaptcha() {
        captcha = nil
        captchaAnswer = ""
    }
}
