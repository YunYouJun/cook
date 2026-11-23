package cn.yunyoujun.cook.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.platform.RecipeShareLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSettingsScreen(snapshot: CookCatalogSnapshot, onOpenRecipeData: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("关于云食册") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
    }) { inset ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SelectionContainer {
                        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                            Text(BuildConfig.APPLICATION_ID, style = MaterialTheme.typography.bodySmall)
                            Text("内置 ${snapshot.data.meta.total} 道菜谱")
                        }
                    }
                    HorizontalDivider()
                    SettingsNavRow("菜谱数据版本", snapshot.data.meta.dataVersion, onOpenRecipeData)
                    HorizontalDivider()
                    AppLinkSettingsRow()
                    HorizontalDivider()
                    SettingsNavRow("更新日志", "查看云食册的版本记录") {
                        context.openExternalLink("https://docs.yunyoujun.cn/projects/cook/changelog")
                    }
                    HorizontalDivider()
                    SettingsNavRow("网页版：食用手册", "在浏览器中打开食用手册") {
                        context.openExternalLink("https://cook.yunyoujun.cn/")
                    }
                    HorizontalDivider()
                    SettingsNavRow("怀旧版本", "在浏览器中打开旧版网页") {
                        context.openExternalLink("https://v1.cook.yunyoujun.cn/")
                    }
                }
            }
            Card(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingsNavRow("在 GitHub 上反馈", "查看与提交项目问题") {
                        context.openExternalLink("https://github.com/YunYouJun/cook/issues")
                    }
                    HorizontalDivider()
                    SettingsNavRow("参与讨论", "打开 GitHub Discussions") {
                        context.openExternalLink("https://github.com/YunYouJun/cook/discussions")
                    }
                }
            }
        }
    }
}

private const val APP_LINK_SETTINGS_HINT = "在系统设置的应用信息中查看「默认打开」"

@Composable
private fun AppLinkSettingsRow() {
    val context = LocalContext.current
    var summary by remember(context) { mutableStateOf(APP_LINK_SETTINGS_HINT) }
    var launchFailed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        summary = context.appLinkSettingsSummary()
        onPauseOrDispose {}
    }
    SettingsNavRow("在云食册中打开链接", summary) {
        launchFailed = !context.openAppLinkSettings()
    }
    if (launchFailed) {
        Text(
            "无法打开系统设置。可在系统设置中搜索云食册，再查看「默认打开」。",
            modifier = Modifier.padding(bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun Context.appLinkSettingsSummary(): String {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return APP_LINK_SETTINGS_HINT
    val state = try {
        getSystemService(DomainVerificationManager::class.java)?.getDomainVerificationUserState(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } ?: return APP_LINK_SETTINGS_HINT
    if (!state.isLinkHandlingAllowed) return "系统已关闭应用链接，可在默认打开设置中开启"

    fun status(host: String?): String = when (state.hostToStateMap[host]) {
        DomainVerificationUserState.DOMAIN_STATE_VERIFIED -> "已通过系统验证"
        DomainVerificationUserState.DOMAIN_STATE_SELECTED -> "已手动关联"
        else -> "未关联"
    }
    return "菜谱分享${status(RecipeShareLinks.HOST)}\n浏览器回跳${status(Uri.parse(YunlefunSSO.REDIRECT_URI).host)}"
}

private fun Context.openAppLinkSettings(): Boolean {
    val app = Uri.fromParts("package", packageName, null)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        runCatching { startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, app)) }.isSuccess
    ) return true
    return runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)) }.isSuccess
}

internal enum class LegalDocument(val title: String, val assetName: String) {
    PRIVACY("隐私政策", "privacy-android.md"),
    TERMS("使用条款", "terms.md"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LegalDocumentScreen(document: LegalDocument, onBack: () -> Unit, onOpenPrivacy: (() -> Unit)? = null) {
    val assets = LocalContext.current.assets
    val content by produceState<Result<String>?>(null, assets, document) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                assets.open("cook/${document.assetName}").bufferedReader().use { it.readText() }
                    .also { check(it.isNotBlank()) }
            }
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(document.title) },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
    }) { inset ->
        // Restore the reading position after the document is available to measure.
        val scroll = if (content?.isSuccess == true) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            modifier = Modifier.fillMaxSize().padding(inset).then(scroll).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val result = content) {
                null -> LinearProgressIndicator(Modifier.widthIn(max = 680.dp).fillMaxWidth())
                else -> result.fold(
                    onSuccess = { LegalDocumentText(it, onOpenPrivacy) },
                    onFailure = { Text("暂时无法读取${document.title}。可联系 me@yunyoujun.cn 获取。") },
                )
            }
        }
    }
}

private val legalInline = Regex("""\[([^\]\n]+)]\(((?:https://|mailto:|/(?!/))[^)\s]*)\)|\*\*([^*\n]+)\*\*""")
private val legalListMarker = Regex("(?m)^- ")
private const val ANDROID_PRIVACY_PATH = "/privacy-android"

@Composable
private fun LegalDocumentText(markdown: String, onOpenPrivacy: (() -> Unit)?) {
    val context = LocalContext.current
    val blocks = remember(markdown) {
        val normalized = markdown.replace("\r\n", "\n")
        val body = if (normalized.startsWith("---\n")) normalized.substringAfter("\n---\n", normalized) else normalized
        body.split("\n\n").map(String::trim).filter(String::isNotEmpty)
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyles = remember(linkColor) {
        TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    }
    SelectionContainer {
        Column(
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for (block in blocks) {
                when {
                    block.startsWith("# ") -> Text(
                        block.removePrefix("# "),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    block.startsWith("## ") -> Text(
                        block.removePrefix("## "),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    else -> Text(
                        remember(block, linkStyles, onOpenPrivacy, context) {
                            buildAnnotatedString {
                                val text = block.replace(legalListMarker, "• ")
                                var offset = 0
                                for (match in legalInline.findAll(text)) {
                                    append(text.substring(offset, match.range.first))
                                    val (label, url, strong) = match.destructured
                                    if (strong.isNotEmpty()) {
                                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(strong) }
                                    } else {
                                        val link = if (url == ANDROID_PRIVACY_PATH && onOpenPrivacy != null) {
                                            LinkAnnotation.Clickable(url, linkStyles) { onOpenPrivacy() }
                                        } else {
                                            val destination = if (url.startsWith('/')) "https://cook.yunle.fun$url" else url
                                            LinkAnnotation.Clickable(destination, linkStyles) {
                                                openLegalLink(context, destination)
                                            }
                                        }
                                        withLink(link) { append(label) }
                                    }
                                    offset = match.range.last + 1
                                }
                                append(text.substring(offset))
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun openLegalLink(context: Context, destination: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
    }.isSuccess
    if (!opened) {
        val text = destination.removePrefix("mailto:")
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("链接", text))
        val message = if (destination.startsWith("mailto:")) "未找到邮件应用，已复制邮箱地址" else "未找到可用的应用，已复制链接"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
