package cn.yunyoujun.cook.data.local

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64

interface SecretVault {
    fun readSecret(key: String): String?
    fun writeSecret(key: String, value: String)
    fun deleteSecret(key: String)
}

sealed class CredentialVaultException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unavailable(cause: Throwable? = null) :
        CredentialVaultException("系统安全密钥库暂时不可用，凭据未写入；已有菜谱数据不受影响。", cause)

    class Corrupted(cause: Throwable? = null) :
        CredentialVaultException("本地安全凭据已损坏或密钥已失效，请重新填写或登录。", cause)
}

/**
 * 基于 Google Tink (`tink-android`) + AndroidKeyStore 主密钥包裹的数据密钥 (AES-256-GCM AEAD) 凭据保险库：
 * - 密钥由 `AndroidKeysetManager` 管理并绑定 `android-keystore://fun.yunle.cook.vault.master.v1`
 * - 密文单独落在 `noBackupFilesDir/credential-vault.enc` 并通过 `.tmp` + `ATOMIC_MOVE`/`.bak` 原子替换写入
 * - 密钥失效或解密失败绝不降级明文、绝不覆盖或清空菜谱库
 */
class AndroidKeystoreVault(
    private val vaultFile: File,
    private val aeadProvider: () -> Aead,
) : SecretVault {
    constructor(
        context: Context,
        alias: String = DEFAULT_KEY_ALIAS,
        fileName: String = DEFAULT_FILE_NAME,
    ) : this(
        vaultFile = File(context.noBackupFilesDir, fileName),
        aeadProvider = { defaultAndroidTinkAead(context.applicationContext, alias) },
    )

    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }
    private val lock = Any()
    @Volatile
    private var cachedAead: Aead? = null

    @Serializable
    private data class EncryptedEnvelope(
        val version: Int = 2,
        val ciphertextBase64: String,
    )

    override fun readSecret(key: String): String? = synchronized(lock) {
        loadMap()[key]
    }

    override fun writeSecret(key: String, value: String) {
        synchronized(lock) {
            val updated = loadMap().toMutableMap()
            updated[key] = value
            saveMap(updated)
        }
    }

    override fun deleteSecret(key: String) {
        synchronized(lock) {
            if (!vaultFile.exists() && !backupFile().exists()) return
            val updated = loadMap().toMutableMap()
            if (updated.remove(key) != null) {
                saveMap(updated)
            }
        }
    }

    private fun backupFile(): File = File(vaultFile.parentFile, "${vaultFile.name}.bak")

    private fun resolveAead(): Aead {
        cachedAead?.let { return it }
        val resolved = runCatching { aeadProvider() }.getOrElse { err ->
            throw CredentialVaultException.Unavailable(err)
        }
        cachedAead = resolved
        return resolved
    }

    private fun decodeMapFromFile(target: File): Map<String, String> {
        val raw = target.readText(Charsets.UTF_8)
        val envelope = runCatching {
            json.decodeFromString<EncryptedEnvelope>(raw)
        }.getOrElse { err ->
            throw CredentialVaultException.Corrupted(err)
        }
        if (envelope.version != 2) {
            throw CredentialVaultException.Corrupted()
        }
        val aead = resolveAead()
        val ciphertext = runCatching { Base64.getDecoder().decode(envelope.ciphertextBase64) }
            .getOrElse { throw CredentialVaultException.Corrupted(it) }
        val plaintext = runCatching {
            aead.decrypt(ciphertext, ASSOCIATED_DATA).toString(Charsets.UTF_8)
        }.getOrElse { err ->
            throw CredentialVaultException.Corrupted(err)
        }
        return runCatching {
            json.decodeFromString<Map<String, String>>(plaintext)
        }.getOrElse { err ->
            throw CredentialVaultException.Corrupted(err)
        }
    }

    private fun loadMap(): Map<String, String> {
        val primaryPresent = vaultFile.exists()
        val bak = backupFile()
        val backupPresent = bak.exists()
        if (!primaryPresent && !backupPresent) return emptyMap()

        var primaryError: Throwable? = null
        if (primaryPresent) {
            try {
                return decodeMapFromFile(vaultFile)
            } catch (e: CredentialVaultException.Unavailable) {
                throw e
            } catch (t: Throwable) {
                primaryError = t
            }
        }
        if (backupPresent) {
            try {
                val recovered = decodeMapFromFile(bak)
                runCatching { saveMap(recovered) }
                return recovered
            } catch (e: CredentialVaultException.Unavailable) {
                throw e
            } catch (t: Throwable) {
                throw (primaryError as? CredentialVaultException ?: CredentialVaultException.Corrupted(t))
            }
        }
        throw (primaryError as? CredentialVaultException ?: CredentialVaultException.Corrupted(primaryError))
    }

    private fun saveMap(map: Map<String, String>) {
        val aead = resolveAead()
        val encoded = json.encodeToString(map).toByteArray(Charsets.UTF_8)
        val envelope = runCatching {
            val encrypted = aead.encrypt(encoded, ASSOCIATED_DATA)
            EncryptedEnvelope(
                version = 2,
                ciphertextBase64 = Base64.getEncoder().encodeToString(encrypted),
            )
        }.getOrElse { err ->
            throw CredentialVaultException.Unavailable(err)
        }

        val serialized = json.encodeToString(envelope)
        runCatching {
            AtomicJsonFiles.writeAtomicText(vaultFile, serialized)
            // A recovered backup must not later resurrect a superseded credential.
            backupFile().delete()
        }.getOrElse { err ->
            throw CredentialVaultException.Unavailable(err)
        }
    }

    companion object {
        private const val DEFAULT_KEY_ALIAS = "fun.yunle.cook.vault.master.v1"
        private const val DEFAULT_FILE_NAME = "credential-vault.enc"
        private const val TINK_KEYSET_NAME = "cook_vault_master_keyset"
        private const val TINK_PREF_FILE = "cook_vault_master_prefs"
        private val ASSOCIATED_DATA = "fun.yunle.cook.vault.v2".toByteArray(Charsets.UTF_8)

        const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        const val KEY_YUNLEFUN_SESSION_JSON = "yunlefun_session_json"
        internal const val KEY_YUNLEFUN_PENDING_SSO = "yunlefun_pending_sso"

        @Volatile
        private var registered = false

        private fun ensureTinkRegistered() {
            if (!registered) {
                synchronized(this) {
                    if (!registered) {
                        AeadConfig.register()
                        registered = true
                    }
                }
            }
        }

        private fun defaultAndroidTinkAead(context: Context, alias: String): Aead {
            ensureTinkRegistered()
            val keysetManager = AndroidKeysetManager.Builder()
                .withSharedPref(context, TINK_KEYSET_NAME, TINK_PREF_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri("android-keystore://$alias")
                .build()
            if (!keysetManager.isUsingKeystore) {
                throw IllegalStateException("AndroidKeystore unavailable; refusing cleartext Tink keyset fallback")
            }
            return keysetManager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }
}
