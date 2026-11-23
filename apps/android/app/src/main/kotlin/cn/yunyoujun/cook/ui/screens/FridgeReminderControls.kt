package cn.yunyoujun.cook.ui.screens

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.platform.KitchenNotificationHelper
import java.time.LocalTime

@Composable
internal fun FridgeReminderControls(preferences: DevicePreferencesStore) {
    val context = LocalContext.current
    val prefs by preferences.state.collectAsState()
    var systemAllowsNotifications by remember { mutableStateOf(KitchenNotificationHelper.canPostFridgeReminders(context)) }
    var attemptedEnable by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val time = LocalTime.of(prefs.fridgeReminderMinutes / 60, prefs.fridgeReminderMinutes % 60)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        systemAllowsNotifications = KitchenNotificationHelper.canPostFridgeReminders(context)
    }

    fun saveEnabled(enabled: Boolean) {
        errorMessage = runCatching { preferences.setFridgeRemindersEnabled(enabled) }
            .exceptionOrNull()?.let { "未能保存提醒设置，请重试。" }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        systemAllowsNotifications = KitchenNotificationHelper.canPostFridgeReminders(context)
        if (systemAllowsNotifications) saveEnabled(true)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().toggleable(
                    value = prefs.fridgeRemindersEnabled,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        attemptedEnable = enabled
                        systemAllowsNotifications = KitchenNotificationHelper.canPostFridgeReminders(context)
                        if (!enabled || systemAllowsNotifications) {
                            saveEnabled(enabled)
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !KitchenNotificationHelper.hasNotificationPermission(context)
                        ) {
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                ).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("冰箱临期提醒", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "保鲜日当天和前一天提醒，同一天合并为一条。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = prefs.fridgeRemindersEnabled, onCheckedChange = null)
            }
            if ((attemptedEnable || prefs.fridgeRemindersEnabled) && !systemAllowsNotifications) {
                Text(
                    "请在系统设置中允许冰箱通知。",
                    modifier = Modifier.padding(horizontal = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        errorMessage = runCatching {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            })
                        }.exceptionOrNull()?.let { "未能打开系统通知设置。" }
                    },
                ) { Text("打开系统通知设置") }
            }
            if (prefs.fridgeRemindersEnabled) {
                TextButton(onClick = { showTimePicker = true }) { Text("每日提醒时间 $time") }
            }
            errorMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showTimePicker) {
        DisposableEffect(context, time) {
            val dialog = TimePickerDialog(context, { _, hour, minute ->
                errorMessage = runCatching { preferences.setFridgeReminderMinutes(hour * 60 + minute) }
                    .exceptionOrNull()?.let { "未能保存提醒时间，请重试。" }
            }, time.hour, time.minute, true)
            dialog.setTitle("每日提醒时间")
            dialog.setOnDismissListener { showTimePicker = false }
            dialog.show()
            onDispose {
                dialog.setOnDismissListener(null)
                dialog.dismiss()
            }
        }
    }
}
