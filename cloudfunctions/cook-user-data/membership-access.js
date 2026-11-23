'use strict'

const MAX_EXPIRY_MS = 253402300799000

/** Use the effective deadline for access checks. Settlement still uses the paid expireAt field. */
function membershipAccessExpiry(record) {
  let expireAt = Number.isFinite(record?.expireAt) && record.expireAt > 0 ? record.expireAt : null
  if (Array.isArray(record?.playSubscriptionAccess)) {
    for (const entry of record.playSubscriptionAccess) {
      const timestamp = entry?.expiresAt
      if (Number.isSafeInteger(timestamp) && timestamp > (expireAt ?? 0) && timestamp <= MAX_EXPIRY_MS) {
        expireAt = timestamp
      }
    }
  }
  return expireAt
}

module.exports = { membershipAccessExpiry }
