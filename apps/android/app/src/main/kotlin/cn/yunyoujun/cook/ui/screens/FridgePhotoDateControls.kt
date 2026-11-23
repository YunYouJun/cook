package cn.yunyoujun.cook.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.data.local.FridgeExpiryBasis
import cn.yunyoujun.cook.data.local.FridgeLabelScan
import cn.yunyoujun.cook.data.local.ProduceStorageAdvice
import cn.yunyoujun.cook.ui.state.FridgePhotoDateReview
import cn.yunyoujun.cook.ui.state.FridgePhotoDates
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private enum class PhotoDateField { PRODUCTION, REFERENCE, PACKAGE, MANUAL }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FridgePhotoDateControls(
    scan: FridgeLabelScan,
    review: FridgePhotoDateReview,
    dates: FridgePhotoDates,
    name: String,
    isProduce: Boolean,
    today: LocalDate,
    onChange: (FridgePhotoDates) -> Unit,
) {
    val focus = LocalFocusManager.current
    var choosing by rememberSaveable { mutableStateOf<PhotoDateField?>(null) }
    fun choose(field: PhotoDateField) {
        focus.clearFocus()
        choosing = field
    }

    Text("核对日期", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { choose(PhotoDateField.PRODUCTION) }) {
            Text(review.production?.let { "生产日期 $it" } ?: "记录生产日期")
        }
        if (review.production != null) {
            TextButton(onClick = {
                val removed = dates.copy(recordProduction = false, productionOverride = null)
                onChange(if (review.basis == FridgeExpiryBasis.CALCULATED) {
                    removed.copy(basis = FridgeExpiryBasis.MANUAL, customExpiry = review.expiry)
                } else removed)
            }) { Text("移除生产日期") }
        }
    }

    if (scan.dateCandidates.isNotEmpty()) {
        Text("识别出的日期可点选为包装到期日", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            scan.dateCandidates.forEach { candidate ->
                FilterChip(
                    selected = review.basis == FridgeExpiryBasis.PACKAGE && candidate == review.expiry,
                    onClick = { onChange(dates.copy(basis = FridgeExpiryBasis.PACKAGE, customExpiry = candidate)) },
                    label = { Text(candidate.toString()) },
                )
            }
        }
    }
    Text(
        review.expiry?.let { day ->
            val nameOfDate = if (review.basis?.isSuggested == true) "建议吃完日期" else "到期日"
            "$nameOfDate $day（${review.kind?.label.orEmpty()}）"
        } ?: "请选择要记录的日期。",
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = { choose(PhotoDateField.PACKAGE) }) { Text("设置包装到期日") }
        if (review.canCalculate && review.basis != FridgeExpiryBasis.CALCULATED) {
            TextButton(onClick = { onChange(dates.copy(basis = FridgeExpiryBasis.CALCULATED, customExpiry = null)) }) {
                Text("按生产日期推算")
            }
        }
        TextButton(onClick = { choose(PhotoDateField.MANUAL) }) { Text("自设食用计划") }
    }
    if (scan.text.isNotBlank()) {
        scan.messages.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    review.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

    if (name.isNotBlank()) {
        Text("按食材安排食用", style = MaterialTheme.typography.titleSmall)
        if (isProduce) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !dates.cut,
                    onClick = { if (dates.cut) onChange(dates.copy(cut = false, startDay = null)) },
                    label = { Text("完整食材") },
                )
                FilterChip(
                    selected = dates.cut,
                    onClick = { if (!dates.cut) onChange(dates.copy(cut = true, startDay = null)) },
                    label = { Text("已经切开或去皮") },
                )
            }
        }
        OutlinedButton(onClick = { choose(PhotoDateField.REFERENCE) }) {
            Text("${if (dates.cut) "切开日期" else "购买日期"} ${review.referenceDay}")
        }
        val advice = review.advice
        Text(
            if (advice == null) "通用估算 ${review.estimate.days} 天，可自行调整。"
            else "建议 ${advice.days} 天内吃完。${advice.condition}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (name == "蓝莓" || name == "西瓜") FridgeProduceExample(name, initiallyExpanded = true)
        else if (advice != null) FridgeStorageSourceLink(advice.sourceUrl)
        OutlinedButton(onClick = { onChange(dates.copy(basis = FridgeExpiryBasis.ESTIMATED, customExpiry = null)) }) {
            Text("采用食用时间建议")
        }
    }

    choosing?.let { field ->
        val title = when (field) {
            PhotoDateField.PRODUCTION -> "生产日期"
            PhotoDateField.REFERENCE -> if (dates.cut) "切开日期" else "购买日期"
            PhotoDateField.PACKAGE -> "包装到期日"
            PhotoDateField.MANUAL -> "自设食用日期"
        }
        val initial = when (field) {
            PhotoDateField.PRODUCTION -> review.production ?: today
            PhotoDateField.REFERENCE -> review.referenceDay
            else -> review.expiry ?: today
        }
        FridgeDatePicker(
            title = title,
            initial = initial,
            latest = today.takeIf { field == PhotoDateField.PRODUCTION || field == PhotoDateField.REFERENCE },
            onDismiss = { choosing = null },
            onSelect = { day ->
                val updated = when (field) {
                    PhotoDateField.PRODUCTION -> dates.copy(recordProduction = true, productionOverride = day)
                    PhotoDateField.REFERENCE -> dates.copy(startDay = day)
                    PhotoDateField.PACKAGE -> dates.copy(basis = FridgeExpiryBasis.PACKAGE, customExpiry = day)
                    PhotoDateField.MANUAL -> dates.copy(basis = FridgeExpiryBasis.MANUAL, customExpiry = day)
                }
                onChange(updated)
                choosing = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FridgeDatePicker(
    title: String,
    initial: LocalDate,
    latest: LocalDate? = null,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val initialDay = latest?.let(initial::coerceAtMost) ?: initial
    val latestMillis = latest?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = minOf(1900, initialDay.year)..maxOf(2100, initialDay.year, latest?.year ?: 0),
        selectableDates = remember(latest) {
            object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = latestMillis == null || utcTimeMillis <= latestMillis
                override fun isSelectableYear(year: Int): Boolean = latest == null || year <= latest.year
            }
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = state.selectedDateMillis != null, onClick = {
                val millis = state.selectedDateMillis ?: return@TextButton
                onSelect(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate())
            }) { Text("应用日期") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消选日期") } },
    ) { DatePicker(state = state, title = { Text(title, Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) }) }
}

@Composable
internal fun FridgeProduceExample(name: String, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable(name) { mutableStateOf(initiallyExpanded) }
    val blueberry = name == "蓝莓"
    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
    ) {
        Text(if (blueberry) "蓝莓没有生产日期怎么记？" else "完整和切开的西瓜怎么记？")
    }
    if (expanded) {
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (blueberry) "确认食材为蓝莓，购买日期填实际买回的那天，再采用食用时间建议。"
                else "完整西瓜可记录购买日期并按成熟度自设食用计划。切开后，选择已经切开或去皮，把切开日期填为实际切开的那天。",
                style = MaterialTheme.typography.bodySmall,
            )
            FridgeStorageSourceLink(ProduceStorageAdvice.UMAINE_SOURCE)
        }
    }
}

@Composable
private fun FridgeStorageSourceLink(url: String) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    TextButton(onClick = {
        runCatching { uriHandler.openUri(url) }
            .onFailure { Toast.makeText(context, "未找到可打开来源的浏览器", Toast.LENGTH_SHORT).show() }
    }) { Text("查看储存建议来源") }
}
