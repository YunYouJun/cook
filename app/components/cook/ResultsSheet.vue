<script lang="ts" setup>
import type { IonSearchbarCustomEvent, SearchbarInputEventDetail } from '@ionic/core'
import type { SearchMode } from '~/composables/store/recipe'
import { yunleHaptic } from '~/utils/yunleHost'

/**
 * 结果底部弹层：分段控件按「齐全 / 差一种 / 差两种」分组，菜名搜索和匹配范围也放在这里
 */
const open = defineModel<boolean>('open', { required: true })

const PAGE = 30
const rStore = useRecipeStore()

const groupLabels = ['齐全', '差一种', '差两种']
const groupHints = ['食材都有', '补一种就能做', '补两种就能做']
const groups = computed(() => groupLabels.map((label, missing) => ({
  label,
  items: rStore.recipeMatches.filter(match => match.missingStuff.length === missing),
})))
const active = ref(0)
const limit = ref(PAGE)
const toolMode = computed(() => !rStore.selectedStuff.length && Boolean(rStore.curTool))

watch(() => rStore.recipeMatches, () => {
  if (!groups.value[active.value]?.items.length)
    active.value = Math.max(0, groups.value.findIndex(group => group.items.length))
  limit.value = PAGE
})

watch(open, (value) => {
  if (value) {
    active.value = Math.max(0, groups.value.findIndex(group => group.items.length))
    limit.value = PAGE
  }
})

const currentMatches = computed(() => groups.value[active.value]?.items ?? [])
const total = computed(() => toolMode.value ? rStore.toolRecipes.length : currentMatches.value.length)
const visibleMatches = computed(() => currentMatches.value.slice(0, limit.value))
const visibleToolRecipes = computed(() => rStore.toolRecipes.slice(0, limit.value))

const modeOptions: { value: SearchMode, label: string }[] = [
  { value: 'loose', label: '全部推荐' },
  { value: 'strict', label: '用上所有已选' },
  { value: 'survival', label: '只看食材齐全' },
]

function onGroupChange(value: unknown) {
  active.value = Number(value) || 0
  limit.value = PAGE
  void yunleHaptic('light')
}

function onKeyword(ev: IonSearchbarCustomEvent<SearchbarInputEventDetail>) {
  rStore.keyword = ev.detail.value ?? ''
}

function onModeChange(value: unknown) {
  rStore.setMode(modeOptions.some(option => option.value === value) ? value as SearchMode : 'loose')
  void yunleHaptic('light')
}
</script>

<template>
  <ion-modal :is-open="open" :breakpoints="[0, 0.6, 1]" :initial-breakpoint="1" @did-dismiss="open = false">
    <ion-header>
      <ion-toolbar>
        <ion-title>{{ toolMode ? `用${rStore.curTool}能做的菜` : '可以做的菜' }}</ion-title>
        <ion-buttons slot="end">
          <ion-button :strong="true" @click="open = false">
            完成
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
      <ion-toolbar>
        <ion-searchbar
          :value="rStore.keyword"
          placeholder="在结果里找菜名"
          :debounce="150"
          show-clear-button="focus"
          @ion-input="onKeyword"
        />
      </ion-toolbar>
      <ion-toolbar v-if="!toolMode" class="results-segment">
        <ion-segment :value="String(active)" @ion-change="onGroupChange($event.detail.value)">
          <ion-segment-button v-for="(group, index) in groups" :key="group.label" :value="String(index)">
            <ion-label>{{ group.label }} {{ group.items.length }}</ion-label>
          </ion-segment-button>
        </ion-segment>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <div class="results-bar">
        <span>{{ total }} 道{{ toolMode ? '' : ` · ${groupHints[active]}` }}</span>
        <ion-select
          v-if="!toolMode"
          class="results-mode"
          aria-label="匹配范围"
          interface="popover"
          :value="rStore.curMode"
          @ion-change="onModeChange($event.detail.value)"
        >
          <ion-select-option v-for="option in modeOptions" :key="option.value" :value="option.value">
            {{ option.label }}
          </ion-select-option>
        </ion-select>
      </div>

      <div v-if="rStore.curTool && !toolMode" class="results-filter">
        <button type="button" class="results-filter-chip" :aria-label="`取消厨具筛选：${rStore.curTool}`" @click="rStore.toggleTools(rStore.curTool)">
          厨具：{{ rStore.curTool }}<ion-icon :icon="ioniconsClose" aria-hidden="true" />
        </button>
      </div>

      <ion-list v-if="total" :inset="true" class="results-list">
        <template v-if="toolMode">
          <RecipeResultRow v-for="(recipe, i) in visibleToolRecipes" :key="`${recipe.bv}-${recipe.name}-${i}`" :recipe="recipe" />
        </template>
        <template v-else>
          <RecipeResultRow v-for="match in visibleMatches" :key="match.sourceIndex" :recipe="match.recipe" :missing="match.missingStuff" />
        </template>
        <ion-item v-if="total > limit" :button="true" :detail="false" class="results-more" @click="limit += PAGE">
          <ion-label color="primary">
            再看 {{ Math.min(PAGE, total - limit) }} 道（还有 {{ total - limit }} 道）
          </ion-label>
        </ion-item>
      </ion-list>

      <div v-else class="cook-empty results-empty">
        <ion-icon :icon="ioniconsRestaurantOutline" />
        <h3>这一组暂时没有菜</h3>
        <p>{{ rStore.keyword || rStore.curMode !== 'loose' ? '试试清除菜名或放宽匹配范围' : '换个分组，或者再加几样食材' }}</p>
      </div>

      <p class="cook-group-footer results-footnote">
        只核对菜谱里记录的食材，西红柿算番茄、鸡翅算鸡肉；常备调料只在菜谱写明时算作已有。点菜名去看做法，用量和调味以视频为准。
      </p>
    </ion-content>
  </ion-modal>
</template>

<style scoped>
.results-segment {
  --padding-bottom: 8px;
}
.results-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 32px 0;
  white-space: nowrap;
  font-size: 13px;
  color: var(--ion-color-step-600, #6d6d72);
}
.results-mode {
  flex: none;
  --padding-end: 0;
  min-height: 32px;
  font-size: 13px;
  color: var(--ion-color-primary);
}
.results-filter {
  padding: 8px 20px 0;
}
.results-filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 10px 0 12px;
  border-radius: 14px;
  background: rgba(var(--ion-color-primary-rgb), 0.14);
  color: var(--ion-color-primary);
  font-size: 13px;
  font-weight: 500;
}
.results-list {
  margin-top: 8px;
}
.results-empty {
  padding-top: 12vh;
}
.results-footnote {
  margin-top: 0;
  padding-bottom: 32px;
}
</style>
