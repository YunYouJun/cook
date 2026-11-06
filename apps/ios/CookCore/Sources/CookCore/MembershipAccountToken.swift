#if os(Linux)
    import Crypto
#else
    import CryptoKit
#endif
import Foundation

public enum MembershipAccountToken {
    /// Platform account marker, shared with the authoritative iap-settlement.js.
    /// This historical domain is an algorithm version, not Cook's bundle/SKU.
    /// The server must independently check both the authenticated UID and bundle.
    public static func forUser(_ userID: String) -> UUID {
        var bytes = Array(SHA256.hash(data: Data(("fun.yunle.apps.iap-owner.v1\0" + userID).utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0f) | 0x80
        bytes[8] = (bytes[8] & 0x3f) | 0x80
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        let cuts = [0, 8, 12, 16, 20, 32]
        let groups = zip(cuts, cuts.dropFirst()).map { start, end in
            String(hex[hex.index(hex.startIndex, offsetBy: start)..<hex.index(hex.startIndex, offsetBy: end)])
        }
        return UUID(uuidString: groups.joined(separator: "-"))!
    }
}
