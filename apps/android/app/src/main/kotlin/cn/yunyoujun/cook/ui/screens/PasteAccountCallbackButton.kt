package cn.yunyoujun.cook.ui.screens

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import cn.yunyoujun.cook.data.remote.YunlefunSSO

@Composable
internal fun PasteAccountCallbackButton(
    label: String,
    enabled: Boolean,
    isExpectedCallback: (String) -> Boolean,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    var pasteFailed by remember { mutableStateOf(false) }

    TextButton(enabled = enabled, onClick = {
        val clip = runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull()
        val callback = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
            ?.takeIf { it.length <= YunlefunSSO.MAX_CALLBACK_LENGTH }
            ?.toString()?.trim()?.takeIf(isExpectedCallback)
        pasteFailed = callback == null
        callback?.let(onSubmit)
    }) { Text(label) }

    if (pasteFailed) {
        Text(
            "剪贴板里没有对应的回调链接，请在浏览器的授权页复制完整链接后重试。",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
