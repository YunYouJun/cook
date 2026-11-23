package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.data.repository.ReviewedRecipe

@Composable
internal fun RecipeDietaryReview(review: ReviewedRecipe, actions: @Composable () -> Unit = {}) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("饮食目标核对 · ${review.goal.title}", style = MaterialTheme.typography.titleSmall)
            Text(review.entry.reason(review.goal), style = MaterialTheme.typography.bodyMedium)
            if (review.goal == DietaryGoal.WEIGHT_AWARE) {
                review.entry.portionOil?.let { oil ->
                    Text(
                        "按原文 ${oil.referenceServings} 人配方均分，保留原文单位。这里只统计配方中另外加入的油，实际吸收量会随烹调而变。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Text(review.goal.explanation, style = MaterialTheme.typography.bodySmall)
            Text("核对日期 ${review.entry.reviewedAt}", style = MaterialTheme.typography.labelMedium)
            actions()
        }
    }
}

@Composable
internal fun MissingRecipeDietaryReview() {
    Text("这道菜的饮食目标核对当前不可用。仍可查看普通菜谱。", style = MaterialTheme.typography.bodyMedium)
}
