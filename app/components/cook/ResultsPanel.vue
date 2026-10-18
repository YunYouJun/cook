<script lang="ts" setup>
import type { IonSearchbarCustomEvent, SearchbarInputEventDetail } from '@ionic/core'
import type { Gesture, GestureDetail } from '@ionic/vue'
import type { RecipeMatch } from '@yunyoujun/cook'
import type { SearchMode } from '~/composables/store/recipe'
import type { RecipeItem } from '~/types'
import { createGesture } from '@ionic/vue'
import { getEmojisFromStuff } from '~/utils'
import { yunleHaptic } from '~/utils/yunleHost'

/**
 * 常驻底部弹层（类似「地图」App）：收起时是一条结果预览，拖到一半能边选食材边看菜，拖到顶是完整列表
 */
const emit = defineEmits<{ scrollTop: [], detail: [recipe: RecipeItem, match?: RecipeMatch] }>()
/** 弹层当前遮住的高度，页面用它留出底部空白，保证最下面的分组能滚出来 */
const inset = defineModel<number>('inset', { default: 0 })

type Detent = 'collapsed' | 'half' | 'full'

/** 收起时露出的高度：把手 + 一行预览 */
const COLLAPSED = 76
const HALF_RATIO = 0.52
/** 完全展开时和页面顶部导航栏之间留的空隙 */
const TOP_GAP = 8
const PAGE = 30

const rStore = useRecipeStore()
const reducedMotion = usePreferredReducedMotion()

// ---------- 尺寸与分档 ----------
const panelRef = ref<HTMLElement>()
const pageEl = shallowRef<HTMLElement | null>(null)
const pageHeight = ref(0)
const headerHeight = ref(0)
const sheetHeight = computed(() => Math.max(COLLAPSED, pageHeight.value - headerHeight.value - TOP_GAP))
const detentOffsets = computed<Record<Detent, number>>(() => ({
  full: 0,
  half: Math.max(0, sheetHeight.value - Math.round(pageHeight.value * HALF_RATIO)),
  collapsed: Math.max(0, sheetHeight.value - COLLAPSED),
}))

const detent = ref<Detent>('collapsed')
const offset = ref(0)
const dragging = ref(false)
/** 首次量好尺寸之前不做过渡，否则冷启动时弹层会从完全展开滑到收起 */
const settled = ref(false)

function measure() {
  const page = pageEl.value
  if (!page)
    return
  pageHeight.value = page.clientHeight
  headerHeight.value = (page.querySelector(':scope > ion-header') as HTMLElement | null)?.offsetHeight ?? 0
  if (!dragging.value)
    snapTo(detent.value)
}

function snapTo(next: Detent) {
  if (next !== detent.value)
    void yunleHaptic('light')
  detent.value = next
  offset.value = detentOffsets.value[next]
  inset.value = next === 'full' ? 0 : sheetHeight.value - offset.value
}

// ---------- 拖动 ----------
let gesture: Gesture | undefined
let startOffset = 0

function onStart() {
  dragging.value = true
  startOffset = offset.value
}

function onMove(detail: GestureDetail) {
  const { full, collapsed } = detentOffsets.value
  let next = startOffset + detail.deltaY
  // 超出两端时加阻尼，和系统弹层一样有一点橡皮筋手感
  if (next < full)
    next = full - (full - next) * 0.25
  else if (next > collapsed)
    next = collapsed + (next - collapsed) * 0.25
  offset.value = next
}

function onEnd(detail: GestureDetail) {
  dragging.value = false
  // 按松手时的速度往前预估一段，再吸附到最近的分档
  const projected = offset.value + detail.velocityY * 180
  const entries = Object.entries(detentOffsets.value) as [Detent, number][]
  const [nearest] = entries.reduce((best, entry) => Math.abs(entry[1] - projected) < Math.abs(best[1] - projected) ? entry : best)
  snapTo(nearest)
}

useResizeObserver(pageEl, measure)

onMounted(() => {
  pageEl.value = panelRef.value?.closest('.ion-page') as HTMLElement | null
  measure()
  requestAnimationFrame(() => requestAnimationFrame(() => settled.value = true))
  if (!panelRef.value)
    return
  gesture = createGesture({
    el: panelRef.value,
    gestureName: 'cook-results-panel',
    direction: 'y',
    threshold: 8,
    // 展开到顶时列表要能正常滚动，只允许从顶部把手区域拖动
    canStart: detail => detent.value !== 'full' || Boolean((detail.event.target as Element | null)?.closest('.results-panel-head')),
    onStart,
    onMove,
    onEnd,
  })
  gesture.enable()
})

onBeforeUnmount(() => gesture?.destroy())

function onHeadClick() {
  snapTo(detent.value === 'collapsed' ? 'half' : 'collapsed')
}

function goAdjust() {
  snapTo('collapsed')
  emit('scrollTop')
}

// 清空食材后收起；选食材时不自动展开，免得挡住正在点的按钮
watch(() => rStore.selectedStuff.length > 0 || Boolean(rStore.curTool), (has) => {
  if (!has)
    snapTo('collapsed')
})

// ---------- 结果数据 ----------
const toolMode = computed(() => !rStore.selectedStuff.length && Boolean(rStore.curTool))
const hasSelection = computed(() => rStore.selectedStuff.length > 0 || Boolean(rStore.curTool))
const groupLabels = ['齐全', '差一种', '差两种']
const groupHints = ['食材都有', '补一种就能做', '补两种就能做']
const groups = computed(() => groupLabels.map((label, missing) => ({
  label,
  items: rStore.recipeMatches.filter(match => match.missingStuff.length === missing),
})))
const active = ref(0)
const slideDirection = ref(1)
const limit = ref(PAGE)

watch(() => rStore.recipeMatches, () => {
  if (!groups.value[active.value]?.items.length)
    active.value = Math.max(0, groups.value.findIndex(group => group.items.length))
  limit.value = PAGE
})

const currentMatches = computed(() => groups.value[active.value]?.items ?? [])
const total = computed(() => toolMode.value ? rStore.toolRecipes.length : currentMatches.value.length)
const visibleMatches = computed(() => currentMatches.value.slice(0, limit.value))
const visibleToolRecipes = computed(() => rStore.toolRecipes.slice(0, limit.value))

function onGroupChange(value: unknown) {
  const next = Number(value) || 0
  slideDirection.value = next >= active.value ? 1 : -1
  active.value = next
  limit.value = PAGE
  void yunleHaptic('light')
}

const modeOptions: { value: SearchMode, label: string }[] = [
  { value: 'loose', label: '全部推荐' },
  { value: 'strict', label: '用上所有已选' },
  { value: 'survival', label: '只看食材齐全' },
]

function onModeChange(value: unknown) {
  rStore.setMode(modeOptions.some(option => option.value === value) ? value as SearchMode : 'loose')
  void yunleHaptic('light')
}

function onKeyword(ev: IonSearchbarCustomEvent<SearchbarInputEventDetail>) {
  rStore.keyword = ev.detail.value ?? ''
}

function addSuggestion(name: string) {
  rStore.addStuff(name)
  void yunleHaptic('success')
}

// ---------- 收起时的预览 ----------
const count = computed(() => rStore.resultCount)
const rollDirection = ref(1)
const delta = ref(0)
const deltaKey = ref(0)
watch(count, (value, oldValue) => {
  rollDirection.value = value >= oldValue ? 1 : -1
  if (!rStore.isSearching && oldValue !== undefined && value !== oldValue) {
    delta.value = value - oldValue
    deltaKey.value++
  }
})

const selectedEmojis = computed(() => {
  const emojis = getEmojisFromStuff(rStore.selectedStuff)
  return emojis.length ? emojis.slice(0, 3) : ['🧺']
})
const selectedSummary = computed(() => {
  const names = [...rStore.selectedStuff]
  if (rStore.curTool)
    names.push(rStore.curTool === '一口大锅' ? '炒锅' : rStore.curTool)
  return names.join('、')
})

// 收起时内容区移到页面以下，会从半透明的标签栏后面透出来；随展开程度淡入淡出（只改 opacity）
const bodyOpacity = computed(() => Math.min(1, Math.max(0, (sheetHeight.value - offset.value - COLLAPSED) / 120)))

const sheetStyle = computed(() => ({
  'height': `${sheetHeight.value}px`,
  'transform': `translate3d(0, ${offset.value}px, 0)`,
  'transition': !settled.value || dragging.value || reducedMotion.value === 'reduce' ? 'none' : undefined,
  '--sheet-visible': `${sheetHeight.value - offset.value}px`,
}))
</script>

<template>
  <div
    ref="panelRef"
    class="results-panel"
    :class="[`is-${detent}`, { 'is-dragging': dragging }]"
    :style="sheetStyle"
    role="region"
    aria-label="可以做的菜"
  >
    <div class="results-panel-head" @click="onHeadClick">
      <div class="results-panel-grabber" aria-hidden="true" />
      <div class="results-panel-bar">
        <template v-if="rStore.loadError">
          <div class="results-panel-text">
            <div class="results-panel-title">
              菜谱没有加载成功
            </div>
            <div class="results-panel-sub">
              {{ rStore.loadError }}
            </div>
          </div>
          <ion-button shape="round" size="small" @click.stop="rStore.loadRecipes">
            重试
          </ion-button>
        </template>
        <template v-else-if="!hasSelection">
          <span class="results-panel-avatars" aria-hidden="true"><span>🧺</span></span>
          <div class="results-panel-text">
            <div class="results-panel-title">
              先选几样食材
            </div>
            <div class="results-panel-sub">
              看看家里的食材能做什么
            </div>
          </div>
          <ion-button shape="round" size="small" fill="outline" @click.stop="rStore.addStuff('番茄'); rStore.addStuff('鸡蛋')">
            试试番茄＋鸡蛋
          </ion-button>
        </template>
        <template v-else>
          <button type="button" class="results-panel-avatars" aria-label="回到顶部调整食材" data-flight-target @click.stop="goAdjust">
            <span v-for="(emoji, i) in selectedEmojis" :key="`${emoji}-${i}`">{{ emoji }}</span>
          </button>
          <div class="results-panel-text">
            <div class="results-panel-title">
              <template v-if="rStore.isSearching">
                正在准备菜谱…
              </template>
              <template v-else>
                {{ toolMode ? `用${rStore.curTool}能做` : '可以做' }}
                <span class="results-panel-count" :style="{ '--roll': rollDirection }">
                  <Transition name="count-roll" mode="out-in">
                    <span :key="count" class="results-panel-count-value">{{ count }}</span>
                  </Transition>
                  <Transition name="count-delta">
                    <span v-if="delta && detent === 'collapsed'" :key="deltaKey" class="results-panel-delta" :class="delta > 0 ? 'is-up' : 'is-down'" aria-hidden="true" @animationend="delta = 0">
                      {{ delta > 0 ? `+${delta}` : delta }}
                    </span>
                  </Transition>
                </span>
                道菜
              </template>
            </div>
            <div class="results-panel-sub">
              {{ selectedSummary }}
            </div>
          </div>
          <span class="results-panel-toggle" aria-hidden="true">
            <ion-icon :icon="ioniconsChevronUp" />
          </span>
        </template>
      </div>
    </div>

    <div class="results-panel-body" :style="{ opacity: bodyOpacity, visibility: bodyOpacity ? 'visible' : 'hidden' }">
      <template v-if="hasSelection && !rStore.loadError">
        <div v-if="rStore.unlockSuggestions.length && !toolMode" class="results-panel-unlock" aria-label="再补一样">
          <span class="results-panel-unlock-label">再补一样</span>
          <TransitionGroup name="unlock" tag="div" class="results-panel-unlock-row">
            <button v-for="item in rStore.unlockSuggestions" :key="item.name" type="button" class="results-panel-unlock-chip" @click="addSuggestion(item.name)">
              <ion-icon :icon="ioniconsAdd" aria-hidden="true" />{{ item.name }}<span>多 {{ item.count }} 道</span>
            </button>
          </TransitionGroup>
        </div>

        <ion-segment v-if="!toolMode" class="results-panel-segment" :value="String(active)" @ion-change="onGroupChange($event.detail.value)">
          <ion-segment-button v-for="(group, index) in groups" :key="group.label" :value="String(index)">
            <ion-label>{{ group.label }} {{ group.items.length }}</ion-label>
          </ion-segment-button>
        </ion-segment>

        <div class="results-panel-scroll">
          <ion-searchbar
            v-if="detent === 'full'"
            class="results-panel-search"
            :value="rStore.keyword"
            placeholder="在结果里找菜名"
            :debounce="150"
            show-clear-button="focus"
            @ion-input="onKeyword"
          />
          <div class="results-panel-summary">
            <span>{{ total }} 道{{ toolMode ? '' : ` · ${groupHints[active]}` }}</span>
            <ion-select
              v-if="!toolMode"
              class="results-panel-mode"
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
          <div v-if="rStore.curTool && !toolMode" class="results-panel-filter">
            <button type="button" class="results-panel-filter-chip" :aria-label="`取消厨具筛选：${rStore.curTool}`" @click="rStore.toggleTools(rStore.curTool)">
              厨具：{{ rStore.curTool }}<ion-icon :icon="ioniconsClose" aria-hidden="true" />
            </button>
          </div>

          <Transition :name="slideDirection > 0 ? 'results-slide-left' : 'results-slide-right'" mode="out-in">
            <ion-list v-if="total" :key="toolMode ? 'tool' : active" :inset="true" class="results-panel-list">
              <template v-if="toolMode">
                <RecipeResultRow
                  v-for="(recipe, i) in visibleToolRecipes"
                  :key="`${recipe.bv}-${recipe.name}-${i}`"
                  :recipe="recipe"
                  :style="{ '--i': Math.min(i, 8) }"
                  @select="emit('detail', recipe)"
                />
              </template>
              <template v-else>
                <RecipeResultRow
                  v-for="(match, i) in visibleMatches"
                  :key="match.sourceIndex"
                  :recipe="match.recipe"
                  :missing="match.missingStuff"
                  :style="{ '--i': Math.min(i, 8) }"
                  @select="emit('detail', match.recipe, match)"
                />
              </template>
              <ion-item v-if="total > limit" :button="true" :detail="false" @click="limit += PAGE">
                <ion-label color="primary">
                  再看 {{ Math.min(PAGE, total - limit) }} 道（还有 {{ total - limit }} 道）
                </ion-label>
              </ion-item>
            </ion-list>
            <div v-else :key="`empty-${active}`" class="results-panel-empty">
              <h3>这一组暂时没有菜</h3>
              <p>{{ rStore.keyword || rStore.curMode !== 'loose' ? '试试清除菜名或放宽匹配范围' : '换个分组，或者再加几样食材' }}</p>
            </div>
          </Transition>

          <p class="cook-group-footer results-panel-footnote">
            只核对菜谱里记录的食材，西红柿算番茄、鸡翅算鸡肉；常备调料只在菜谱写明时算作已有。用量和调味以视频为准。
          </p>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.results-panel {
  position: absolute;
  z-index: 20;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  border-radius: 12px 12px 0 0;
  background: var(--ion-background-color, #f2f2f6);
  box-shadow:
    0 -0.5px 0 var(--ion-color-step-150, rgba(0, 0, 0, 0.12)),
    0 -6px 24px rgba(0, 0, 0, 0.08);
  /* iOS 弹层的减速曲线 */
  transition: transform 0.42s cubic-bezier(0.32, 0.72, 0, 1);
  will-change: transform;
}
.results-panel-head {
  flex: none;
  padding: 6px 16px 10px;
  cursor: grab;
}
.results-panel-grabber {
  width: 36px;
  height: 5px;
  margin: 0 auto 8px;
  border-radius: 3px;
  background: var(--ion-color-step-300, rgba(118, 118, 128, 0.4));
}
.results-panel-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
}
.results-panel-avatars {
  display: inline-flex;
  flex: none;
  padding-inline-start: 6px;
}
.results-panel-avatars span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  margin-inline-start: -6px;
  border: 2px solid var(--ion-background-color, #f2f2f6);
  border-radius: 50%;
  background: var(--ion-item-background, #fff);
  font-size: 17px;
}
.results-panel-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}
.results-panel-title {
  font-size: 17px;
  font-weight: 600;
  line-height: 22px;
  color: var(--ion-text-color, #000);
}
.results-panel-sub {
  font-size: 13px;
  line-height: 18px;
  color: var(--ion-color-medium);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.results-panel-toggle {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--cook-fill);
  color: var(--ion-color-medium);
  font-size: 16px;
  transition: transform 0.3s cubic-bezier(0.32, 0.72, 0, 1);
}
.results-panel:not(.is-collapsed) .results-panel-toggle {
  transform: rotate(180deg);
}
.results-panel-count {
  position: relative;
  display: inline-flex;
  vertical-align: bottom;
}
.results-panel-count-value {
  display: inline-block;
  min-width: 1ch;
  font-variant-numeric: tabular-nums;
}
.results-panel-delta {
  position: absolute;
  left: 50%;
  bottom: 100%;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
  pointer-events: none;
  animation: results-delta 0.9s cubic-bezier(0.2, 0.8, 0.2, 1) forwards;
}
.results-panel-delta.is-up {
  color: var(--ion-color-primary);
}
.results-panel-delta.is-down {
  color: var(--ion-color-medium);
}
@keyframes results-delta {
  0% {
    opacity: 0;
    transform: translate(-50%, 6px) scale(0.8);
  }
  25% {
    opacity: 1;
    transform: translate(-50%, 0) scale(1);
  }
  100% {
    opacity: 0;
    transform: translate(-50%, -10px);
  }
}
.results-panel-body {
  transition: opacity 0.3s ease;
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}
.results-panel-unlock {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 0 10px 16px;
}
.results-panel-unlock-label {
  flex: none;
  font-size: 13px;
  color: var(--ion-color-medium);
}
.results-panel-unlock-row {
  position: relative;
  display: flex;
  gap: 8px;
  padding-inline-end: 16px;
  overflow-x: auto;
  scrollbar-width: none;
}
.results-panel-unlock-row::-webkit-scrollbar {
  display: none;
}
.results-panel-unlock-chip {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 3px;
  height: 30px;
  padding: 0 12px 0 8px;
  border-radius: 15px;
  background: rgba(var(--ion-color-primary-rgb), 0.14);
  color: var(--ion-color-primary);
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
}
.results-panel-unlock-chip span {
  margin-inline-start: 4px;
  font-size: 12px;
  font-weight: 400;
  opacity: 0.8;
}
.results-panel-unlock-chip:active {
  transform: scale(0.94);
}
.results-panel-segment {
  flex: none;
  width: auto;
  margin: 0 16px;
}
.results-panel-scroll {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  overscroll-behavior: contain;
}
.results-panel.is-dragging .results-panel-body {
  transition: none;
}
.results-panel.is-full .results-panel-scroll {
  overflow-y: auto;
}
.results-panel-search {
  padding: 10px 12px 0;
}
.results-panel-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 32px 0;
  font-size: 13px;
  color: var(--ion-color-step-600, #6d6d72);
  white-space: nowrap;
}
.results-panel-mode {
  flex: none;
  --padding-end: 0;
  min-height: 32px;
  font-size: 13px;
  color: var(--ion-color-primary);
}
.results-panel-filter {
  padding: 4px 20px 0;
}
.results-panel-filter-chip {
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
.results-panel-list {
  margin-top: 8px;
}
/* 展开或切换分组时，前几行依次浮现 */
.results-panel-list :deep(.recipe-result-row) {
  animation: results-row-in 0.36s cubic-bezier(0.2, 0.8, 0.2, 1) both;
  animation-delay: calc(var(--i, 0) * 24ms);
}
@keyframes results-row-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
}
.results-panel-empty {
  padding: 32px;
  text-align: center;
  color: var(--ion-color-medium);
}
.results-panel-empty h3 {
  margin: 0 0 6px;
  font-size: 17px;
  font-weight: 600;
  color: var(--ion-text-color, #000);
}
.results-panel-empty p {
  margin: 0;
  font-size: 15px;
}
.results-panel-footnote {
  margin-top: 0;
  padding-bottom: 24px;
}

/* 分段切换：列表按方向滑入 */
.results-slide-left-enter-active,
.results-slide-left-leave-active,
.results-slide-right-enter-active,
.results-slide-right-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.2s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.results-slide-left-enter-from,
.results-slide-right-leave-to {
  opacity: 0;
  transform: translateX(24px);
}
.results-slide-left-leave-to,
.results-slide-right-enter-from {
  opacity: 0;
  transform: translateX(-24px);
}

.unlock-enter-active,
.unlock-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.24s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.unlock-enter-from,
.unlock-leave-to {
  opacity: 0;
  transform: scale(0.85);
}
.unlock-leave-active {
  position: absolute;
}

.count-roll-enter-active,
.count-roll-leave-active {
  transition:
    transform 0.14s ease,
    opacity 0.14s ease;
}
.count-roll-enter-from {
  opacity: 0;
  transform: translateY(calc(var(--roll) * 0.8em));
}
.count-roll-leave-to {
  opacity: 0;
  transform: translateY(calc(var(--roll) * -0.8em));
}

@media (prefers-reduced-motion: reduce) {
  .results-panel,
  .results-panel-toggle,
  .count-roll-enter-active,
  .count-roll-leave-active,
  .unlock-enter-active,
  .unlock-leave-active {
    transition: none;
  }
  .results-panel-list :deep(.recipe-result-row),
  .results-panel-delta {
    animation: none;
  }
  .results-slide-left-enter-active,
  .results-slide-left-leave-active,
  .results-slide-right-enter-active,
  .results-slide-right-leave-active {
    transition: opacity 0.12s ease;
  }
  .results-slide-left-enter-from,
  .results-slide-left-leave-to,
  .results-slide-right-enter-from,
  .results-slide-right-leave-to {
    transform: none;
  }
}
</style>
