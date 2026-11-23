package cn.yunyoujun.cook.ui.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import java.time.ZonedDateTime

@Composable
internal fun rememberLocalCalendarClock(
    updateEveryMinute: Boolean = false,
    readLocalTime: () -> ZonedDateTime = ZonedDateTime::now,
): ZonedDateTime {
    val context = LocalContext.current
    var clock by remember(readLocalTime) { mutableStateOf(readLocalTime()) }
    LifecycleStartEffect(context, updateEveryMinute, readLocalTime) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                clock = readLocalTime()
            }
        }
        val filter = IntentFilter(Intent.ACTION_DATE_CHANGED).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            if (updateEveryMinute) addAction(Intent.ACTION_TIME_TICK)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        clock = readLocalTime()
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    return clock
}
