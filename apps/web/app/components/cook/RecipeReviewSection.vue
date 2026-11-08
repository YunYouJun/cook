<script setup lang="ts">
import type { RecipeItem } from '~/types'
import {
  dietaryGoalDetails,
  dietaryReviewReason,
  discoverySceneDetails,
  formatOilReference,
} from '~/utils/reviewed-recipes'

const props = defineProps<{ recipe: RecipeItem }>()
const rStore = useRecipeStore()

const dietaryEntry = computed(() => rStore.dietaryReview(props.recipe.id))
const goal = computed(() => rStore.dietaryGoal)
const institutionEntry = computed(() => rStore.discoveryScene === 'institutional-low-gi'
  ? rStore.institutionalReview(props.recipe.id)
  : undefined)
const lunchEntry = computed(() => rStore.discoveryScene === 'next-day-lunch'
  ? rStore.lunchReview(props.recipe.id)
  : undefined)
const lunchMeta = computed(() => rStore.reviewedCatalog?.lunchMeta)
const chineseReferences = computed(() => rStore.reviewedCatalog?.institutionalChineseReferences ?? [])

const dietaryReason = computed(() => {
  if (!goal.value || !dietaryEntry.value)
    return ''
  return dietaryReviewReason(goal.value, dietaryEntry.value)
})

const weightOil = computed(() => {
  const oil = dietaryEntry.value?.portionOil
  if (!oil)
    return undefined
  const range = oil.batchOilGrams ?? oil.batchOilMilliliters
  const unit = oil.batchOilGrams ? 'g' : 'ml'
  if (!range)
    return undefined
  return {
    batch: formatOilReference(oil.referenceServings, range, unit),
    serving: formatOilReference(oil.referenceServings, range, unit, true),
    referenceServings: oil.referenceServings,
    unit,
  }
})
</script>

<template>
  <div v-if="dietaryEntry || lunchEntry || institutionEntry" class="recipe-review">
    <section v-if="dietaryEntry && goal" class="recipe-review-card" :aria-label="`${dietaryGoalDetails[goal].label}审核依据`">
      <h3 class="cook-group-header">
        {{ dietaryGoalDetails[goal].label }}
      </h3>
      <div class="recipe-review-body">
        <p class="recipe-review-primary">
          {{ dietaryReason }}
        </p>
        <p v-if="goal === 'weight-aware' && weightOil">
          按原文 {{ weightOil.referenceServings }} 人份的整锅配方，额外烹调油为 {{ weightOil.batch }}；均分后每人 {{ weightOil.serving }}。保留原文 {{ weightOil.unit }} 单位，不换算为重量、总脂肪或实际摄入油量。
        </p>
        <p>{{ dietaryGoalDetails[goal].explanation }}</p>
        <p class="recipe-review-meta">
          核对日期：{{ dietaryEntry.reviewedAt }} · 结论绑定当前菜谱字段和内置图文快照；外部原文站点更新后需重新审核
        </p>
      </div>
    </section>

    <section v-if="lunchEntry" class="recipe-review-card" aria-label="次日便当审核依据">
      <h3 class="cook-group-header">
        次日便当
      </h3>
      <div class="recipe-review-body">
        <p class="recipe-review-primary">
          按原文 {{ lunchEntry.referenceServings }} 人份核对，额外烹调油 {{ formatOilReference(lunchEntry.referenceServings, lunchEntry.addedOil, lunchEntry.addedOil.unit) }}（{{ lunchEntry.addedOil.unit }} 原单位）。
        </p>
        <p v-if="lunchEntry.addedOil.note">
          {{ lunchEntry.addedOil.note }}
        </p>
        <p v-if="lunchEntry.packingNote">
          {{ lunchEntry.packingNote }}
        </p>
        <p>{{ lunchMeta?.scope ?? discoverySceneDetails['next-day-lunch'].explanation }}</p>
        <a class="recipe-review-link" href="https://cook.yunle.fun/guide/next-day-lunch" target="_blank" rel="noopener noreferrer">
          查看冷却、冷藏和复热指南
        </a>
        <p v-if="lunchMeta?.reviewedAt" class="recipe-review-meta">
          核对日期：{{ lunchMeta.reviewedAt }}
        </p>
      </div>
    </section>

    <section v-if="institutionEntry" class="recipe-review-card" aria-label="机构低 GI 食谱来源与边界">
      <h3 class="cook-group-header">
        机构低 GI 食谱
      </h3>
      <div class="recipe-review-body">
        <p>{{ discoverySceneDetails['institutional-low-gi'].explanation }}</p>
        <dl class="recipe-review-facts">
          <div>
            <dt>英文原方</dt>
            <dd>{{ institutionEntry.sourceRecipeTitle }}</dd>
          </div>
          <div>
            <dt>作者</dt>
            <dd>{{ institutionEntry.author }}</dd>
          </div>
          <div>
            <dt>配方条件</dt>
            <dd>{{ institutionEntry.requirements }}</dd>
          </div>
          <div>
            <dt>核对日期</dt>
            <dd>{{ institutionEntry.reviewedAt }}</dd>
          </div>
        </dl>
        <a class="recipe-review-link" :href="institutionEntry.sourceURL" target="_blank" rel="noopener noreferrer">
          打开 GI News 英文原方
        </a>
      </div>
    </section>

    <section v-if="institutionEntry && chineseReferences.length" class="recipe-review-card" aria-label="中文概念与测定参考">
      <h3 class="cook-group-header">
        中文概念与测定参考
      </h3>
      <div class="recipe-review-body">
        <p>以下材料不是英文原方的中文版、来源或认证，也不提供本批配方的实测 GI 值。</p>
        <ul class="recipe-review-references">
          <li v-for="reference in chineseReferences" :key="reference.id">
            <a :href="reference.url" target="_blank" rel="noopener noreferrer">{{ reference.title }}</a>
            <span>{{ reference.publisher }} · {{ reference.scope }}</span>
          </li>
        </ul>
      </div>
    </section>
  </div>
</template>

<style scoped>
.recipe-review {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}
.recipe-review-card {
  overflow-wrap: anywhere;
}
.recipe-review-body {
  display: grid;
  gap: 8px;
  margin: 0 16px;
  padding: 12px 16px;
  border-radius: 10px;
  background: var(--ion-item-background, #fff);
  font-size: 1rem;
  line-height: 1.6;
}
.recipe-review-body p {
  margin: 0;
  color: var(--ion-color-step-600, #6d6d72);
}
.recipe-review-body .recipe-review-primary {
  color: var(--ion-text-color, #000);
  font-weight: 650;
}
.recipe-review-body .recipe-review-meta {
  color: var(--ion-color-medium);
  font-size: 0.8rem;
}
.recipe-review-link {
  justify-self: start;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  color: var(--ion-color-primary);
  font-weight: 600;
}
.recipe-review-facts {
  display: grid;
  gap: 8px;
  margin: 0;
}
.recipe-review-facts div {
  display: grid;
  grid-template-columns: minmax(5.5em, auto) minmax(0, 1fr);
  gap: 10px;
}
.recipe-review-facts dt {
  color: var(--ion-color-medium);
}
.recipe-review-facts dd {
  margin: 0;
  color: var(--ion-text-color, #000);
}
.recipe-review-references {
  display: grid;
  gap: 10px;
  margin: 0;
  padding-inline-start: 1.15rem;
}
.recipe-review-references li {
  padding-inline-start: 2px;
}
.recipe-review-references a,
.recipe-review-references span {
  display: block;
}
.recipe-review-references a {
  min-height: 32px;
  color: var(--ion-color-primary);
  font-weight: 600;
}
.recipe-review-references span {
  color: var(--ion-color-step-600, #6d6d72);
  font-size: 0.86rem;
  line-height: 1.5;
}
@media (max-width: 420px) {
  .recipe-review-facts div {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
