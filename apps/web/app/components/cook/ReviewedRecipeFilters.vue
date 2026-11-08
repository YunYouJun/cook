<script setup lang="ts">
import type { DietaryGoalID, DiscoverySceneID, ReviewedFilterOption } from '~/utils/reviewed-recipes'
import { computed, shallowRef } from 'vue'

const props = defineProps<{
  goalOptions: ReviewedFilterOption<DietaryGoalID>[]
  sceneOptions: ReviewedFilterOption<DiscoverySceneID>[]
  selectedGoal: DietaryGoalID | null
  selectedScene: DiscoverySceneID | null
  loading: boolean
  loaded: boolean
  unavailableMessage: string
}>()
const emit = defineEmits<{
  selectGoal: [goal: DietaryGoalID]
  selectScene: [scene: DiscoverySceneID]
  load: []
}>()

const expanded = shallowRef(false)
function toggleExpanded() {
  expanded.value = !expanded.value
  if (expanded.value && !props.loaded && !props.loading)
    emit('load')
}

const enabledGoals = computed(() => props.goalOptions.filter(option => option.enabled))
const enabledScenes = computed(() => props.sceneOptions.filter(option => option.enabled))
const selectedGoal = computed(() => props.goalOptions.find(option => option.id === props.selectedGoal))
const selectedScene = computed(() => props.sceneOptions.find(option => option.id === props.selectedScene))
const hasOptions = computed(() => enabledGoals.value.length > 0 || enabledScenes.value.length > 0)
</script>

<template>
  <div class="reviewed-filters">
    <button type="button" class="reviewed-filters-toggle" :aria-expanded="expanded" @click="toggleExpanded">
      饮食目标与找菜专题 <span aria-hidden="true">{{ expanded ? '−' : '＋' }}</span>
    </button>
    <div v-if="expanded" aria-live="polite">
      <p v-if="loading" class="reviewed-filters-status" role="status">
        正在核对饮食目标与专题来源…
      </p>
      <p v-else-if="unavailableMessage" class="reviewed-filters-status" role="status">
        {{ unavailableMessage }}
        <button type="button" @click="emit('load')">
          重试加载
        </button>
      </p>
      <template v-else-if="hasOptions">
        <IngredientSection v-if="enabledGoals.length" title="饮食目标（可选，一次一个）" :note="selectedGoal ? selectedGoal.label : ''">
          <IngredientChip
            v-for="option in enabledGoals"
            :key="option.id"
            :label="`${option.label} · ${option.count}`"
            :selected="selectedGoal?.id === option.id"
            @click="emit('selectGoal', option.id)"
          />
        </IngredientSection>
        <IngredientSection v-if="enabledScenes.length" title="找菜专题（可选）" :note="selectedScene ? selectedScene.label : ''">
          <IngredientChip
            v-for="option in enabledScenes"
            :key="option.id"
            :label="`${option.label} · ${option.count}`"
            :selected="selectedScene?.id === option.id"
            @click="emit('selectScene', option.id)"
          />
        </IngredientSection>
        <div v-if="selectedGoal || selectedScene" class="reviewed-filters-detail">
          <p v-if="selectedGoal">
            <strong>{{ selectedGoal.label }}：</strong>{{ selectedGoal.explanation }}
          </p>
          <p v-if="selectedScene">
            <strong>{{ selectedScene.label }}：</strong>{{ selectedScene.explanation }}
          </p>
        </div>
        <p v-else class="reviewed-filters-hint">
          只展示审核记录仍与当前做法一致的菜谱；可与食材、厨具、忌口和做法继续取交集。
        </p>
      </template>
      <p v-else class="reviewed-filters-status" role="status">
        已审核筛选暂不可用，普通找菜仍可继续。
      </p>
    </div>
  </div>
</template>

<style scoped>
.reviewed-filters-toggle {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: calc(100% - 32px);
  min-height: 44px;
  margin: 12px 16px 0;
  padding: 10px 12px;
  border: 0;
  border-radius: 8px;
  background: var(--ion-item-background, #fff);
  color: var(--ion-text-color, #000);
  font: inherit;
  text-align: start;
  cursor: pointer;
}
.reviewed-filters-toggle:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 2px;
}
.reviewed-filters-status,
.reviewed-filters-hint {
  margin: 12px 16px 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--ion-item-background, #fff);
  color: var(--ion-color-medium);
  font-size: 13px;
  line-height: 1.55;
}
.reviewed-filters-detail {
  display: grid;
  gap: 6px;
  margin: 10px 16px 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--ion-item-background, #fff);
}
.reviewed-filters-detail p {
  margin: 0;
  color: var(--ion-color-step-600, #6d6d72);
  font-size: 13px;
  line-height: 1.55;
}
.reviewed-filters-detail strong {
  color: var(--ion-text-color, #000);
}
@media (min-width: 1024px) {
  .reviewed-filters-status,
  .reviewed-filters-hint,
  .reviewed-filters-detail {
    margin-inline: 16px;
  }
}
</style>
