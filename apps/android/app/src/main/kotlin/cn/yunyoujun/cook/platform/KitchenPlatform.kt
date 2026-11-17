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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.yunyoujun.cook.CookApplication
import cn.yunyoujun.cook.MainActivity
import cn.yunyoujun.cook.data.local.RecipeTimerState
import kotlin.math.sqrt

enum class AlarmSchedulePrecision {
    EXACT,
    INEXACT_FALLBACK,
    NOT_SCHEDULED,
}

object KitchenNotificationHelper {
    const val CHANNEL_TIMER = "cook_kitchen_timer"
    const val CHANNEL_FRIDGE = "cook_fridge_expiry"

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
        if (!hasNotificationPermission(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
        manager.notify(10_000 + recipeId, notification)
    }

    fun showFridgeDueNotification(context: Context, dueCount: Int) {
        if (dueCount <= 0) return
        ensureChannels(context)
        if (!hasNotificationPermission(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            20_001,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_FRIDGE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("冰箱里有 $dueCount 项食材临期或待用")
            .setContentText("打开冰箱看看食材保鲜日，顺手做道菜不浪费。")
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(20_001, notification)
    }
}

object KitchenTimerScheduler {
    private const val ACTION_TIMER_EXPIRED = "cn.yunyoujun.cook.ACTION_KITCHEN_TIMER_EXPIRED"
    private const val EXTRA_RECIPE_ID = "recipe_id"
    private const val EXTRA_RECIPE_NAME = "recipe_name"

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return alarmManager.canScheduleExactAlarms()
    }

    fun schedule(
        context: Context,
        recipeId: Int,
        recipeName: String,
        deadlineEpochMs: Long,
    ): AlarmSchedulePrecision {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            ?: return AlarmSchedulePrecision.NOT_SCHEDULED
        val pendingIntent = buildPendingIntent(context, recipeId, recipeName)
        return if (canScheduleExactAlarms(context)) {
            runCatching {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineEpochMs, pendingIntent)
                AlarmSchedulePrecision.EXACT
            }.getOrElse {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineEpochMs, pendingIntent)
                AlarmSchedulePrecision.INEXACT_FALLBACK
            }
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadlineEpochMs, pendingIntent)
            AlarmSchedulePrecision.INEXACT_FALLBACK
        }
    }

    fun cancel(context: Context, recipeId: Int, recipeName: String = "") {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildPendingIntent(context, recipeId, recipeName)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    fun reschedulePersistedTimers(context: Context) {
        val app = context.applicationContext as? CookApplication ?: return
        val nowMs = System.currentTimeMillis()
        val entries = app.container.workbenchStore.state.value.entries
        for ((recipeId, entry) in entries) {
            val deadline = entry.timer.deadlineEpochMs ?: continue
            if (!entry.timer.running) continue
            if (deadline <= nowMs) {
                app.container.workbenchStore.updateTimer(
                    recipeId,
                    RecipeTimerState(
                        durationSeconds = entry.timer.durationSeconds,
                        running = false,
                        deadlineEpochMs = null,
                        pausedRemainingSeconds = 0,
                    ),
                )
            } else {
                schedule(context, recipeId, "菜谱 #$recipeId", deadline)
            }
        }
    }

    private fun buildPendingIntent(context: Context, recipeId: Int, recipeName: String): PendingIntent {
        val intent = Intent(context, KitchenTimerReceiver::class.java).apply {
            action = ACTION_TIMER_EXPIRED
            putExtra(EXTRA_RECIPE_ID, recipeId)
            putExtra(EXTRA_RECIPE_NAME, recipeName)
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            recipeId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    class KitchenTimerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TIMER_EXPIRED -> {
                    val recipeId = intent.getIntExtra(EXTRA_RECIPE_ID, -1)
                    val recipeName = intent.getStringExtra(EXTRA_RECIPE_NAME) ?: "厨房计时"
                    if (recipeId >= 0) {
                        val app = context.applicationContext as? CookApplication
                        app?.container?.workbenchStore?.let { store ->
                            val current = store.entry(recipeId).timer
                            store.updateTimer(
                                recipeId,
                                current.copy(running = false, deadlineEpochMs = null, pausedRemainingSeconds = 0),
                            )
                        }
                        KitchenNotificationHelper.showTimerDoneNotification(context, recipeId, recipeName)
                    }
                }
                FridgeDailyReminderScheduler.ACTION_FRIDGE_DAILY_CHECK -> {
                    FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(context)
                    FridgeDailyReminderScheduler.scheduleNextDailyCheck(context)
                }
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
                -> {
                    reschedulePersistedTimers(context)
                    FridgeDailyReminderScheduler.checkAndNotifyIfNeeded(context)
                    FridgeDailyReminderScheduler.scheduleNextDailyCheck(context)
                }
            }
        }
    }
}

/**
 * 冰箱保鲜每日去重提醒调度器：
 * - 按当地自然日 (`LocalDate.now()`) 去重，每日至多发送 1 条临期/待用提醒（容忍 Doze 延迟）
 * - 启动、录入食材、系统重启或时间变更时检查
 */
object FridgeDailyReminderScheduler {
    const val ACTION_FRIDGE_DAILY_CHECK = "cn.yunyoujun.cook.ACTION_FRIDGE_DAILY_CHECK"
    private const val REQUEST_CODE_FRIDGE_DAILY = 30_001

    fun checkAndNotifyIfNeeded(context: Context, today: java.time.LocalDate = java.time.LocalDate.now()): Boolean {
        if (!KitchenNotificationHelper.hasNotificationPermission(context)) {
            return false
        }
        val app = context.applicationContext as? CookApplication ?: return false
        val todayIso = today.toString()
        val prefs = app.container.preferencesStore
        if (prefs.current.lastFridgeReminderDate == todayIso) {
            return false
        }
        val items = app.container.fridgeStore.state.value.items
        val dueCount = items.count { item ->
            val rem = item.daysRemaining(today)
            rem != null && rem <= 2L
        }
        if (dueCount <= 0) return false
        KitchenNotificationHelper.showFridgeDueNotification(context, dueCount)
        prefs.recordFridgeReminderSent(todayIso)
        return true
    }

    fun scheduleNextDailyCheck(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, KitchenTimerScheduler.KitchenTimerReceiver::class.java).apply {
            action = ACTION_FRIDGE_DAILY_CHECK
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE_FRIDGE_DAILY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val nextTriggerMs = System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_DAY
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextTriggerMs,
            AlarmManager.INTERVAL_HALF_DAY,
            pendingIntent,
        )
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
