package cn.yunyoujun.cook.data.local

import android.content.Context
import android.content.SharedPreferences
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.RecommendationMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

internal const val DEFAULT_FRIDGE_REMINDER_MINUTES = 9 * 60

@Serializable
enum class AppThemeMode(val title: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

@Serializable
enum class SignalConsentState {
    UNDECIDED,
    ALLOWED,
    DECLINED,
}

@Serializable
enum class AiProviderMode(val title: String) {
    DISABLED("关闭 AI 辅助"),
    BYOK_DEEPSEEK("个人 DeepSeek Key"),
    MANAGED_MEMBERSHIP("云乐坊会员托管 DeepSeek"),
}

@Serializable
data class DevicePreferencesSnapshot(
    val pantry: List<String> = emptyList(),
    val pantryInitialized: Boolean = false,
    val avoid: List<String> = emptyList(),
    val noSpicy: Boolean = false,
    val mode: RecommendationMode = RecommendationMode.LOOSE,
    val rememberedDietaryGoal: DietaryGoal? = null,
    val hasCompletedDietaryIntroduction: Boolean = false,
    val signalConsent: SignalConsentState = SignalConsentState.UNDECIDED,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val aiProviderMode: AiProviderMode = AiProviderMode.DISABLED,
    val fridgeRemindersEnabled: Boolean = false,
    val fridgeReminderMinutes: Int = DEFAULT_FRIDGE_REMINDER_MINUTES,
    val lastFridgeReminderDate: String? = null,
    val recentSearches: List<String> = emptyList(),
    val cloudKitchen: Boolean = false,
    val cloudKitchenRotation: Boolean = false,
)

class DevicePreferencesStore(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(readSnapshot())
    val state: StateFlow<DevicePreferencesSnapshot> = _state.asStateFlow()

    val current: DevicePreferencesSnapshot
        get() = _state.value

    fun ensureDefaultPantry(defaults: List<String>) {
        if (_state.value.pantryInitialized) return
        update { prev ->
            if (prev.pantryInitialized) prev
            else prev.copy(pantry = defaults.distinct(), pantryInitialized = true)
        }
    }

    fun setPantry(pantry: List<String>) {
        update { it.copy(pantry = pantry.distinct(), pantryInitialized = true) }
    }

    fun togglePantryItem(item: String) {
        update { prev ->
            val next = if (item in prev.pantry) prev.pantry - item else prev.pantry + item
            prev.copy(pantry = next, pantryInitialized = true)
        }
    }

    fun setAvoid(avoid: List<String>) {
        update { it.copy(avoid = avoid.distinct()) }
    }

    fun toggleAvoidItem(item: String) {
        update { prev ->
            val next = if (item in prev.avoid) prev.avoid - item else prev.avoid + item
            prev.copy(avoid = next)
        }
    }

    fun setNoSpicy(noSpicy: Boolean) {
        update { it.copy(noSpicy = noSpicy) }
    }

    fun applySearchPreferences(avoid: List<String>, noSpicy: Boolean, goal: DietaryGoal? = null) {
        if (avoid.isEmpty() && !noSpicy && goal == null) return
        update {
            it.copy(
                avoid = (it.avoid + avoid).distinct(),
                noSpicy = it.noSpicy || noSpicy,
                rememberedDietaryGoal = goal ?: it.rememberedDietaryGoal,
                hasCompletedDietaryIntroduction = goal != null || it.hasCompletedDietaryIntroduction,
            )
        }
    }

    fun setMode(mode: RecommendationMode) {
        update { it.copy(mode = mode) }
    }

    fun completeDietaryIntroduction() {
        update { it.copy(hasCompletedDietaryIntroduction = true) }
    }

    fun selectDietaryGoal(goal: DietaryGoal?) {
        update {
            it.copy(
                rememberedDietaryGoal = goal,
                hasCompletedDietaryIntroduction = true,
            )
        }
    }

    fun setSignalConsent(allowed: Boolean) {
        update {
            it.copy(signalConsent = if (allowed) SignalConsentState.ALLOWED else SignalConsentState.DECLINED)
        }
    }

    fun setThemeMode(themeMode: AppThemeMode) {
        update { it.copy(themeMode = themeMode) }
    }

    fun setCloudKitchen(enabled: Boolean) = update { it.copy(cloudKitchen = enabled) }

    fun setCloudKitchenRotation(enabled: Boolean) = update { it.copy(cloudKitchenRotation = enabled) }

    fun setAiProviderMode(mode: AiProviderMode) {
        update { it.copy(aiProviderMode = mode) }
    }

    fun setFridgeRemindersEnabled(enabled: Boolean) = update(durable = true) { it.copy(fridgeRemindersEnabled = enabled) }

    fun setFridgeReminderMinutes(minutes: Int) {
        require(minutes in 0 until 24 * 60)
        update(durable = true) { it.copy(fridgeReminderMinutes = minutes) }
    }

    fun recordFridgeReminderSent(isoLocalDate: String) = update(durable = true) { it.copy(lastFridgeReminderDate = isoLocalDate) }

    fun recordSearch(raw: String) {
        val text = raw.trim().split(Regex("\\s+")).joinToString(" ").take(300)
        if (text.isBlank()) return
        fun identity(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val key = identity(text)
        update { prev ->
            prev.copy(recentSearches = (listOf(text) + prev.recentSearches.filterNot { identity(it) == key }).take(10))
        }
    }

    fun removeSearch(text: String) = update { it.copy(recentSearches = it.recentSearches - text) }

    fun clearSearches() = update { it.copy(recentSearches = emptyList()) }

    internal fun restoreBackup(backup: BackupPreferences) = update(durable = true, transform = backup::applyingTo)

    private fun update(
        durable: Boolean = false,
        transform: (DevicePreferencesSnapshot) -> DevicePreferencesSnapshot,
    ) {
        synchronized(this) {
            val next = transform(_state.value)
            val edit = prefs.edit().putString(KEY_SNAPSHOT_JSON, json.encodeToString(next))
            if (durable) check(edit.commit()) { "无法写入做菜偏好。" } else edit.apply()
            _state.value = next
        }
    }

    private fun readSnapshot(): DevicePreferencesSnapshot {
        val raw = prefs.getString(KEY_SNAPSHOT_JSON, null) ?: return DevicePreferencesSnapshot()
        return runCatching { json.decodeFromString<DevicePreferencesSnapshot>(raw) }
            .map { value ->
                if (value.fridgeReminderMinutes in 0 until 24 * 60) value
                else value.copy(fridgeReminderMinutes = DEFAULT_FRIDGE_REMINDER_MINUTES)
            }
            .getOrDefault(DevicePreferencesSnapshot())
    }

    companion object {
        private const val PREFS_NAME = "cook_device_preferences"
        private const val KEY_SNAPSHOT_JSON = "snapshot_v1"
    }
}
