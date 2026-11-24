import Foundation

enum CookAIAuthorization: Sendable, Equatable {
    case yunlefunBearer(YunlefunCredential)
    case appleJWS(String)

    var headerValue: String {
        switch self {
        case let .yunlefunBearer(credential):
            return "Bearer \(credential.accessToken)"
        case let .appleJWS(jws):
            return "AppleJWS \(jws)"
        }
    }

    var isValidForTransmission: Bool {
        switch self {
        case let .yunlefunBearer(credential):
            return credential.expiresAt > Date()
                && !credential.subject.isEmpty
                && !credential.accessToken.isEmpty
                && credential.accessToken.utf8.count <= 16_384
                && credential.accessToken.unicodeScalars.allSatisfy { (33 ... 126).contains($0.value) }
        case let .appleJWS(jws):
            let parts = jws.split(separator: ".", omittingEmptySubsequences: false)
            return parts.count == 3
                && !jws.isEmpty
                && jws.utf8.count <= 16_384
                && parts.allSatisfy { !$0.isEmpty && $0.unicodeScalars.allSatisfy { scalar in
                    (65 ... 90).contains(scalar.value)
                        || (97 ... 122).contains(scalar.value)
                        || (48 ... 57).contains(scalar.value)
                        || scalar == "-" || scalar == "_"
                } }
        }
    }
}
