package cn.yunyoujun.cook.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.data.local.KitchenClockReading
import cn.yunyoujun.cook.data.local.KitchenWorkbenchStore
import cn.yunyoujun.cook.data.local.RecipeTimerState
import cn.yunyoujun.cook.data.local.RecipeTimerState.Phase
import cn.yunyoujun.cook.platform.AlarmSchedulePrecision
import cn.yunyoujun.cook.platform.KitchenNotificationHelper
import cn.yunyoujun.cook.platform.KitchenTimerScheduler
import kotlinx.coroutines.delay

@Composable
internal fun KitchenTimerCard(
    recipeId: Int,
    recipeName: String,
    timer: RecipeTimerState,
    store: KitchenWorkbenchStore,
    dietaryGoal: DietaryGoal? = null,
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(KitchenTimerScheduler.clockReading(context)) }
    LaunchedEffect(timer) {
        now = KitchenTimerScheduler.clockReading(context)
        while (timer.phase(now) == Phase.RUNNING) {
            delay(1000)
            now = KitchenTimerScheduler.clockReading(context)
        }
    }
    val phase = timer.phase(now)
    var durationInput by rememberSaveable(recipeId) { mutableStateOf<String?>(null) }
    var precision by remember { mutableStateOf<AlarmSchedulePrecision?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    var settingsFailed by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        now = KitchenTimerScheduler.clockReading(context)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        now = KitchenTimerScheduler.clockReading(context)
        if (precision == AlarmSchedulePrecision.INEXACT_FALLBACK && KitchenTimerScheduler.canScheduleExactAlarms(context)) {
            precision = null
        }
    }

    fun changeTimer(transform: (RecipeTimerState, KitchenClockReading) -> RecipeTimerState): Boolean {
        val stamp = KitchenTimerScheduler.clockReading(context)
        now = stamp
        val current = store.entry(recipeId).timer
        val next = transform(current, stamp)
        if (next == current) return true
        saveFailed = runCatching { store.updateTimer(recipeId, next) }.isFailure
        if (saveFailed) return false

        settingsFailed = false
        current.alarmKey?.let { KitchenTimerScheduler.cancel(context, recipeId, it) }
        KitchenNotificationHelper.cancelTimerNotification(context, recipeId)
        precision = next.alarmKey?.let { KitchenTimerScheduler.schedule(context, recipeId, recipeName, next) }
        if (next.running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !KitchenNotificationHelper.hasNotificationPermission(context)
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        return true
    }

    val remaining = timer.remainingSeconds(now)
    val countdown = if (remaining >= 3600) {
        "%d:%02d:%02d".format(remaining / 3600, remaining / 60 % 60, remaining % 60)
    } else {
        "%02d:%02d".format(remaining / 60, remaining % 60)
    }
    val notificationAllowed = KitchenNotificationHelper.canPostTimerReminders(context)
    val exactAllowed = KitchenTimerScheduler.canScheduleExactAlarms(context)
    val hint = if (phase == Phase.RUNNING) when {
        !KitchenNotificationHelper.hasNotificationPermission(context) ->
            "未授予系统通知权限，返回工作台可查看剩余时间。"
        !notificationAllowed ->
            "厨房计时通知已关闭，可在系统设置中开启。"
        precision == AlarmSchedulePrecision.NOT_SCHEDULED ->
            "未能设置后台提醒，返回工作台可查看剩余时间。"
        precision == AlarmSchedulePrecision.INEXACT_FALLBACK || !exactAllowed ->
            "当前系统未授权精确闹钟，后台到时通知可能延迟。"
        precision == AlarmSchedulePrecision.EXACT -> "已设定后台精确提醒。"
        else -> null
    } else null
    val status = when (phase) {
        Phase.READY -> "准备好后开始"
        Phase.RUNNING -> "计时中"
        Phase.PAUSED -> "已暂停"
        Phase.FINISHED -> "计时结束"
    }
    val action = when (phase) {
        Phase.READY -> "开始计时"
        Phase.RUNNING -> "暂停"
        Phase.PAUSED -> "继续"
        Phase.FINISHED -> "再计一次"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本菜计时器", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
                Text(countdown, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (phase == Phase.READY) {
                    OutlinedButton(onClick = {
                        saveFailed = false
                        durationInput = (timer.durationSeconds / 60).toString()
                    }) {
                        Text("时长 ${timer.durationSeconds / 60} 分钟")
                    }
                }
                Button(onClick = {
                    changeTimer { current, now ->
                        if (current.phase(now) != phase) current
                        else if (phase == Phase.RUNNING) current.pause(now)
                        else current.start(now, recipeName, dietaryGoal)
                    }
                }) { Text(action) }
                if (phase != Phase.READY) {
                    OutlinedButton(onClick = { changeTimer { current, _ -> RecipeTimerState(current.durationSeconds) } }) {
                        Text("重置")
                    }
                }
            }
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (phase == Phase.RUNNING && (!notificationAllowed || !exactAllowed)) {
                val notificationSettings = !notificationAllowed
                TextButton(
                    onClick = {
                        val intent = if (notificationSettings) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        } else {
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
                        }
                        settingsFailed = runCatching { context.startActivity(intent) }.isFailure
                    },
                ) {
                    Text(if (notificationSettings) "打开系统通知设置" else "允许精确提醒")
                }
            }
            if (settingsFailed) {
                Text("未能打开系统设置。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (saveFailed) {
                Text("计时器未能保存，请重试。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    durationInput?.let { input ->
        val minutes = input.toIntOrNull()?.takeIf { it in RecipeTimerState.DURATION_MINUTES }
        AlertDialog(
            onDismissRequest = { durationInput = null },
            title = { Text("计时时长") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        if (value.length <= 3 && value.all(Char::isDigit)) durationInput = value
                    },
                    label = { Text("分钟（1–180）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = input.isNotEmpty() && minutes == null,
                    supportingText = if (saveFailed) ({ Text("计时器未能保存，请重试。") }) else null,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = minutes != null,
                    onClick = {
                        val duration = requireNotNull(minutes) * 60
                        if (changeTimer { current, _ ->
                                if (current.running) current else RecipeTimerState(durationSeconds = duration)
                            }
                        ) durationInput = null
                    },
                ) { Text("完成") }
            },
            dismissButton = { TextButton(onClick = { durationInput = null }) { Text("取消") } },
        )
    }
}
