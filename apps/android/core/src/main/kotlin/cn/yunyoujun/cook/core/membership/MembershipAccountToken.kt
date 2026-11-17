package cn.yunyoujun.cook.core.membership

import java.security.MessageDigest
import java.util.UUID

/**
 * 跨端会员账号标记，与 iOS CookCore `MembershipAccountToken` 及服务端 `iap-settlement.js` 保持一致。
 * 此处固定前缀属于算法版本标记，非应用包名。
 */
object MembershipAccountToken {
    private const val DOMAIN = "fun.yunle.apps.iap-owner.v1\u0000"

    fun forUser(userId: String): UUID {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((DOMAIN + userId).toByteArray(Charsets.UTF_8))
            .copyOf(16)
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x80).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = digest.joinToString(separator = "") { "%02x".format(it) }
        val uuidString = buildString {
            append(hex, 0, 8)
            append('-')
            append(hex, 8, 12)
            append('-')
            append(hex, 12, 16)
            append('-')
            append(hex, 16, 20)
            append('-')
            append(hex, 20, 32)
        }
        return UUID.fromString(uuidString)
    }
}
