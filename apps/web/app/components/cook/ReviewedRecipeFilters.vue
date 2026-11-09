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
const selectedCount = computed(() => Number(Boolean(props.selectedGoal)) + Number(Boolean(props.selectedScene)))

function clearSelection() {
  if (props.selectedGoal)
    emit('selectGoal', props.selectedGoal)
  if (props.selectedScene)
    emit('selectScene', props.selectedScene)
}
</script>

<template>
  <button
    type="button"
    class="reviewed-filters-toggle"
    :aria-label="selectedCount ? `饮食与专题筛选，已选 ${selectedCount} 项` : '饮食与专题筛选'"
    aria-haspopup="dialog"
    :aria-expanded="expanded"
    @click="toggleExpanded"
  >
    筛选 <span v-if="selectedCount" class="reviewed-filters-count" aria-hidden="true">{{ selectedCount }}</span>
  </button>
  <ion-modal
    class="reviewed-filters-sheet"
    :is-open="expanded"
    :breakpoints="[0, 0.75, 1]"
    :initial-breakpoint="0.75"
    aria-label="饮食与专题筛选"
    @did-dismiss="expanded = false"
  >
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-button :disabled="!selectedCount" aria-label="清除饮食与专题筛选" @click="clearSelection">
            清除
          </ion-button>
        </ion-buttons>
        <ion-title>饮食与专题</ion-title>
        <ion-buttons slot="end">
          <ion-button :strong="true" @click="expanded = false">
            完成
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content class="reviewed-filters-content">
      <p v-if="loading" class="reviewed-filters-status" role="status">
        正在加载饮食与专题…
      </p>
      <p v-else-if="unavailableMessage" class="reviewed-filters-status" role="status">
        {{ unavailableMessage }}
        <button type="button" class="reviewed-filters-retry" @click="emit('load')">
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
          按饮食习惯或带饭场景找菜，还能继续选择食材、厨具和忌口。
        </p>
      </template>
      <p v-else class="reviewed-filters-status" role="status">
        已审核筛选暂不可用，普通找菜仍可继续。
      </p>
    </ion-content>
  </ion-modal>
</template>

<style scoped>
.reviewed-filters-toggle {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  gap: 4px;
  min-width: 44px;
  min-height: 44px;
  padding: 0 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ion-color-primary);
  font: inherit;
  font-size: 17px;
  cursor: pointer;
}
.reviewed-filters-count {
  display: grid;
  place-items: center;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border-radius: 9px;
  background: var(--ion-color-primary);
  color: var(--ion-color-primary-contrast);
  font-size: 11px;
  font-weight: 600;
}
.reviewed-filters-content {
  --padding-bottom: calc(20px + env(safe-area-inset-bottom));
}
.reviewed-filters-toggle:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: -2px;
}
.reviewed-filters-retry {
  display: block;
  min-height: 44px;
  color: var(--ion-color-primary);
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
