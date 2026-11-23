package cn.yunyoujun.cook.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.yunyoujun.cook.CookApplication
import cn.yunyoujun.cook.MainActivity
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.KitchenClockReading
import cn.yunyoujun.cook.data.local.RecipeTimerState
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.sqrt

enum class AlarmSchedulePrecision {
    EXACT,
    INEXACT_FALLBACK,
    NOT_SCHEDULED,
}

object KitchenNotificationHelper {
    const val CHANNEL_TIMER = "cook_kitchen_timer"
    const val CHANNEL_FRIDGE = "cook_fridge_expiry"
    internal const val ACTION_OPEN_FRIDGE = "cn.yunyoujun.cook.ACTION_OPEN_FRIDGE"
    private const val ACTION_OPEN_TIMER = "cn.yunyoujun.cook.ACTION_OPEN_TIMER"
    private const val EXTRA_TIMER_RECIPE_ID = "timer_recipe_id"
    private const val FRIDGE_NOTIFICATION_ID = 20_001

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_TIMER) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TIMER,
                    "厨房计时器",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "菜谱步骤倒计时结束提醒"
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_FRIDGE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FRIDGE,
                    "冰箱保鲜提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "食材临期与建议尽早食用提示"
                },
            )
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun showTimerDoneNotification(context: Context, recipeId: Int, recipeName: String) {
        ensureChannels(context)
        if (!canPostTimerReminders(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_TIMER
            putExtra(EXTRA_TIMER_RECIPE_ID, recipeId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            recipeId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_TIMER)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("计时结束：$recipeName")
            .setContentText("厨房工作台倒计时已到，请回到厨房查看火候。")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        try {
            manager.notify(10_000 + recipeId, notification)
        } catch (_: SecurityException) {
            // Notification access can change after the check above.
        }
    }

    internal fun timerRecipeFromNotification(intent: Intent): Int? =
        if (intent.action == ACTION_OPEN_TIMER) intent.getIntExtra(EXTRA_TIMER_RECIPE_ID, -1).takeIf { it >= 0 } else null

    internal fun cancelTimerNotification(context: Context, recipeId: Int) {
        context.getSystemService(NotificationManager::class.java)?.cancel(10_000 + recipeId)
    }

    internal fun canPostTimerReminders(context: Context): Boolean {
        if (!hasNotificationPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(CHANNEL_TIMER) ?: return false
        return manager.areNotificationsEnabled() && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun canPostFridgeReminders(context: Context): Boolean {
        if (!hasNotificationPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(CHANNEL_FRIDGE) ?: return false
        return manager.areNotificationsEnabled() && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    internal fun showFridgeDueNotification(context: Context, due: List<FridgeBatchItem>, today: LocalDate): Boolean {
        if (due.isEmpty() || !canPostFridgeReminders(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_FRIDGE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            FRIDGE_NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = buildList {
            for ((day, label) in listOf(today to "今天", today.plusDays(1) to "明天")) {
                val (suggested, packaged) = due.filter { it.expiresOnIsoDate == day.toString() }
                    .partition { it.dateKind.isSuggested }
                if (packaged.isNotEmpty()) add("${fridgeNames(packaged)}${label}到期")
                if (suggested.isNotEmpty()) add("${fridgeNames(suggested)}建议${label}吃完")
            }
        }.joinToString("，")
        val notification = NotificationCompat.Builder(context, CHANNEL_FRIDGE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("冰箱里有食材需要留意")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        return try {
            manager.notify(FRIDGE_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    internal fun cancelFridgeDueNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(FRIDGE_NOTIFICATION_ID)
    }

    private fun fridgeNames(due: List<FridgeBatchItem>): String {
        val names = due.map { it.ingredientName }.distinct()
        val first = names.take(3).joinToString("、")
        return if (names.size > 3) "$first 等 ${names.size} 样" else first
    }
}

object KitchenTimerScheduler {
    private const val ACTION_TIMER_EXPIRED = "cn.yunyoujun.cook.ACTION_KITCHEN_TIMER_EXPIRED"
    private const val EXTRA_RECIPE_ID = "recipe_id"
    private const val EXTRA_RECIPE_NAME = "recipe_name"
    private const val EXTRA_DEADLINE = "deadline_epoch_ms"
    private const val EXTRA_ALARM_KEY = "timer_alarm_key"

    internal fun clockReading(context: Context): KitchenClockReading {
        val bootCount = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
            .getOrDefault(-1).takeIf { it >= 0 }
        return KitchenClockReading(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootCount)
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    internal fun schedule(
        context: Context,
        recipeId: Int,
        recipeName: String,
        timer: RecipeTimerState,
        now: KitchenClockReading = clockReading(context),
    ): AlarmSchedulePrecision {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return AlarmSchedulePrecision.NOT_SCHEDULED
        val key = timer.alarmKey ?: return AlarmSchedulePrecision.NOT_SCHEDULED
        val elapsed = timer.elapsedDeadline(now)
        val trigger = elapsed ?: requireNotNull(timer.deadlineEpochMs)
        val type = if (elapsed == null) AlarmManager.RTC_WAKEUP else AlarmManager.ELAPSED_REALTIME_WAKEUP
        fun scheduleBackup(): Boolean {
            val backup = timerPendingIntent(context, recipeId, key, recipeName, backup = true) ?: return false
            return runCatching { alarmManager.setAndAllowWhileIdle(type, trigger, backup) }.isSuccess
        }

        // Revocation removes exact alarms and stops the process. Register the independent backup first.
        val backupScheduled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) scheduleBackup() else null
        if (canScheduleExactAlarms(context)) {
            val primary = timerPendingIntent(context, recipeId, key, recipeName)
            if (primary != null) {
                try {
                    alarmManager.setExactAndAllowWhileIdle(type, trigger, primary)
                    return AlarmSchedulePrecision.EXACT
                } catch (_: SecurityException) {
                    // Exact alarm access can change between the check and scheduling.
                }
            }
        }
        return if (backupScheduled ?: scheduleBackup()) AlarmSchedulePrecision.INEXACT_FALLBACK
        else AlarmSchedulePrecision.NOT_SCHEDULED
    }

    internal fun cancel(context: Context, recipeId: Int, key: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        fun cancelExisting(alarmKey: String?, backup: Boolean = false) {
            timerPendingIntent(context, recipeId, alarmKey, backup = backup, existingOnly = true)?.let { pendingIntent ->
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
        cancelExisting(key)
        cancelExisting(key, backup = true)
        cancelExisting(null)
    }

    private fun finishIfDue(app: CookApplication, recipeId: Int, key: String, recipeName: String, now: KitchenClockReading) {
        val completed = runCatching { app.container.workbenchStore.finishTimer(recipeId, key, now) }
            .getOrElse { error ->
                Log.w("KitchenTimer", "Could not save completed timer", error)
                return
            }
        if (completed) {
            cancel(app, recipeId, key)
            KitchenNotificationHelper.showTimerDoneNotification(app, recipeId, recipeName)
        }
    }

    fun reschedulePersistedTimers(context: Context, wallClockChanged: Boolean = false) {
        val app = context.applicationContext as? CookApplication ?: return
        val now = clockReading(context)
        val store = app.container.workbenchStore
        val entries = store.state.value.entries
        for ((recipeId, entry) in entries) {
            val timer = entry.timer
            val key = timer.alarmKey ?: continue
            if (!timer.running) continue
            val recipeName = timer.recipeName ?: "菜谱 #$recipeId"
            if (timer.phase(now) == RecipeTimerState.Phase.FINISHED) {
                finishIfDue(app, recipeId, key, recipeName, now)
                continue
            }
            val restored = timer.restoreClock(now, wallClockChanged)
            val active = if (restored == timer) timer else runCatching {
                store.updateTimer(recipeId, restored)
                if (restored.alarmKey != key) cancel(app, recipeId, key)
                restored
            }.getOrElse { error ->
                Log.w("KitchenTimer", "Could not update the timer clock", error)
                timer
            }
            schedule(context, recipeId, recipeName, active, now)
        }
    }

    private fun timerPendingIntent(
        context: Context,
        recipeId: Int,
        key: String?,
        recipeName: String? = null,
        backup: Boolean = false,
        existingOnly: Boolean = false,
    ): PendingIntent? {
        val intent = Intent(context, KitchenTimerReceiver::class.java).apply {
            action = ACTION_TIMER_EXPIRED
            if (key != null) {
                val suffix = if (backup) "/backup" else ""
                data = Uri.fromParts("cook-kitchen-timer", "$recipeId/$key$suffix", null)
                putExtra(EXTRA_ALARM_KEY, key)
            }
            putExtra(EXTRA_RECIPE_ID, recipeId)
            putExtra(EXTRA_RECIPE_NAME, recipeName)
        }
        val lookup = if (existingOnly) PendingIntent.FLAG_NO_CREATE else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(
            context.applicationContext,
            recipeId,
            intent,
            lookup or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    class KitchenTimerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TIMER_EXPIRED -> {
                    val recipeId = intent.getIntExtra(EXTRA_RECIPE_ID, -1)
                    if (recipeId < 0) return
                    val app = context.applicationContext as? CookApplication ?: return
                    val timer = app.container.workbenchStore.entry(recipeId).timer
                    val key = intent.getStringExtra(EXTRA_ALARM_KEY)
                        ?: intent.getLongExtra(EXTRA_DEADLINE, 0L).takeIf { it > 0 }?.toString()
                        ?: timer.alarmKey.takeIf { timer.runId == null } ?: return
                    val recipeName = timer.recipeName ?: intent.getStringExtra(EXTRA_RECIPE_NAME) ?: "菜谱 #$recipeId"
                    finishIfDue(app, recipeId, key, recipeName, clockReading(context))
                }
                FridgeDailyReminderScheduler.ACTION_FRIDGE_DAILY_CHECK -> {
                    FridgeDailyReminderScheduler.onAlarm(context, intent)
                }
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                -> {
                    reschedulePersistedTimers(context, wallClockChanged = intent.action == Intent.ACTION_TIME_CHANGED)
                    FridgeDailyReminderScheduler.reschedule(context)
                }
            }
        }
    }
}

object FridgeDailyReminderScheduler {
    const val ACTION_FRIDGE_DAILY_CHECK = "cn.yunyoujun.cook.ACTION_FRIDGE_DAILY_CHECK"
    private const val REQUEST_CODE_FRIDGE_DAILY = 30_001
    private const val EXTRA_SCHEDULED_AT = "fridge_reminder_scheduled_at"

    internal fun onAlarm(context: Context, intent: Intent, now: ZonedDateTime = ZonedDateTime.now()) {
        val app = context.applicationContext as? CookApplication ?: return
        val store = app.container.preferencesStore
        val prefs = store.current
        val today = now.toLocalDate()
        val expected = localReminderTime(today, prefs.fridgeReminderMinutes, now.zone)
        var sentToday = false
        if (prefs.fridgeRemindersEnabled && !now.isBefore(expected) &&
            intent.getLongExtra(EXTRA_SCHEDULED_AT, -1) == expected.toInstant().toEpochMilli() &&
            prefs.lastFridgeReminderDate != today.toString()
        ) {
            val dueDates = setOf(today.toString(), today.plusDays(1).toString())
            val due = app.container.fridgeStore.state.value.stock.filter { it.expiresOnIsoDate in dueDates }
            if (KitchenNotificationHelper.showFridgeDueNotification(context, due, today)) {
                sentToday = true
                runCatching { store.recordFridgeReminderSent(today.toString()) }
                    .onFailure { Log.w("FridgeReminder", "Could not save last reminder date", it) }
            }
        }
        reschedule(context, now, justSent = today.toString().takeIf { sentToday })
    }

    // All callers run on the main thread, including the stock and settings observer.
    internal fun reschedule(context: Context, now: ZonedDateTime = ZonedDateTime.now(), justSent: String? = null) {
        val app = context.applicationContext as? CookApplication ?: return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val prefs = app.container.preferencesStore.current
        val intent = Intent(context, KitchenTimerScheduler.KitchenTimerReceiver::class.java).apply {
            action = ACTION_FRIDGE_DAILY_CHECK
        }
        val next = if (prefs.fridgeRemindersEnabled && KitchenNotificationHelper.canPostFridgeReminders(context)) {
            nextReminderAt(app.container.fridgeStore.state.value.stock, prefs.fridgeReminderMinutes, now, justSent ?: prefs.lastFridgeReminderDate)
        } else null
        if (next == null) {
            PendingIntent.getBroadcast(
                app, REQUEST_CODE_FRIDGE_DAILY, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
            if (!prefs.fridgeRemindersEnabled) KitchenNotificationHelper.cancelFridgeDueNotification(context)
            return
        }
        val triggerMs = next.toInstant().toEpochMilli()
        intent.putExtra(EXTRA_SCHEDULED_AT, triggerMs)
        val pendingIntent = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE_FRIDGE_DAILY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
    }

    internal fun nextReminderAt(
        stock: List<FridgeBatchItem>,
        minutes: Int,
        now: ZonedDateTime,
        lastSent: String?,
    ): ZonedDateTime? {
        val today = now.toLocalDate()
        return stock.asSequence()
            .filter { it.consumedAtMs == null }
            .mapNotNull { item -> item.expiresOnIsoDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } }
            .filter { it >= today }
            .flatMap { expiry -> sequenceOf(expiry.minusDays(1), expiry) }
            .filter { it >= today && it.toString() != lastSent }
            .distinct()
            .mapNotNull { day -> localReminderTime(day, minutes, now.zone).takeIf { it.toLocalDate() == day } }
            // A delayed alarm may still be waiting for delivery after the scheduled local time.
            .minOrNull()
    }

    private fun localReminderTime(day: LocalDate, minutes: Int, zone: ZoneId): ZonedDateTime {
        val time = day.atTime(minutes / 60, minutes % 60)
        val afterGap = zone.rules.getTransition(time)?.takeIf { it.isGap }?.dateTimeAfter
        return (afterGap ?: time).atZone(zone)
    }
}

class DeviceShakeDetector(
    context: Context,
    private val onShake: () -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var lastTriggerMs = 0L

    val hasAccelerometer: Boolean
        get() = accelerometer != null

    fun start() {
        val sensor = accelerometer ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (values.size < 3) return
        val gForce = sqrt(
            (values[0] / SensorManager.GRAVITY_EARTH) * (values[0] / SensorManager.GRAVITY_EARTH) +
                (values[1] / SensorManager.GRAVITY_EARTH) * (values[1] / SensorManager.GRAVITY_EARTH) +
                (values[2] / SensorManager.GRAVITY_EARTH) * (values[2] / SensorManager.GRAVITY_EARTH),
        )
        val now = System.currentTimeMillis()
        if (gForce >= 2.25f && now - lastTriggerMs >= 900L) {
            lastTriggerMs = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
