package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.core.dietary.InstitutionalLowGICatalog

internal fun LazyListScope.institutionalLowGIReview(
    entry: InstitutionalLowGICatalog.Entry,
    references: List<InstitutionalLowGICatalog.ChineseReference>,
    onOpen: (String) -> Unit,
) {
    item(key = "institutional-recipe-source") { InstitutionalLowGIRecipeSource(entry, onOpen) }
    item(key = "institutional-source-scope") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.classificationTitle, style = MaterialTheme.typography.titleSmall)
            Text(
                InstitutionalLowGICatalog.EXPLANATION,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onOpen(InstitutionalLowGICatalog.AUTHORITY_URL) }) {
                Text("机构版权与食谱分析范围（英文）")
            }
        }
    }
    item(key = "institutional-references-heading") {
        Text("中文权威参考", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    }
    items(references, key = { "institutional-reference-${it.id}" }) { reference ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onOpen(reference.url) }) { Text(reference.title) }
            Text(
                "${reference.publisher} · ${reference.scope}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
internal fun InstitutionalLowGIRecipeSource(entry: InstitutionalLowGICatalog.Entry, onOpen: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(InstitutionalLowGICatalog.TAG, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(entry.sourceRecipeTitle, style = MaterialTheme.typography.titleSmall)
            Text("发布机构 ${InstitutionalLowGICatalog.PUBLISHER}", style = MaterialTheme.typography.bodyMedium)
            Text("作者 ${entry.author}", style = MaterialTheme.typography.bodyMedium)
            Text("核对日期 ${entry.reviewedAt}", style = MaterialTheme.typography.bodySmall)
            Text("原方要求", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            Text(entry.requirements, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { onOpen(entry.sourceURL) }) { Text("打开机构原方（英文，需联网）") }
        }
    }
}
