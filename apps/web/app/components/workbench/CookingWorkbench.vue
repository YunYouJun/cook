<script setup lang="ts">
import type { RecipeMatch } from '@yunyoujun/cook'
import type { RecipeItem, StuffItem } from '~/types'
import { DIFFICULTY_LEVELS, METHOD_GROUPS } from '@yunyoujun/cook/recommend'
import { useWorkbenchCatalog } from '~/composables/useWorkbenchCatalog'
import { methodIcons, toolIcons } from '~/data/cookingIcons'
import { pantryOptions } from '~/data/pantry'

const props = defineProps<{ desktop?: boolean }>()
defineEmits<{ detail: [recipe: RecipeItem, match?: RecipeMatch] }>()
const pantry = defineModel<string[]>('pantry', { required: true })
const rStore = useRecipeStore()
const pointerFine = useMediaQuery('(hover: hover) and (pointer: fine)')
const reducedMotion = usePreferredReducedMotion()
const { sections: categories, ingredients: allFood, tools, acceptsIngredient } = useWorkbenchCatalog(() => rStore.curTool)
const category = ref(0)
const query = ref('')
const expanded = ref(false)
// Desktop always shows settings without changing the narrow-screen disclosure choices.
const pantryExpanded = shallowRef(false)
const filtersExpanded = shallowRef(false)
const avoidOpen = ref(false)
const matches = computed(() => rStore.recommendWithPantry(pantry.value))
const toolOnly = computed(() => !rStore.selectedStuff.length && Boolean(rStore.curTool))
const count = computed(() => toolOnly.value ? rStore.toolRecipes.length : matches.value.length)
const limit = ref(8)
const resultsHeading = ref<HTMLElement>()
const dragActive = ref(false)
const dragType = 'application/x-cook-ingredient'

const visibleItems = computed(() => {
  const text = query.value.trim()
  if (text)
    return allFood.value.filter(item => item.name.includes(text) || item.alias?.includes(text))
  return categories.value[category.value]!.items.filter(item => props.desktop || expanded.value || !item.more || rStore.selectedStuff.includes(item.name))
})
const hasMore = computed(() => categories.value[category.value]!.items.some(item => item.more))
const preferenceSummary = computed(() => [
  ...rStore.methodGroups,
  ...rStore.difficulty,
  ...(rStore.noSpicy ? ['不吃辣'] : []),
  ...(rStore.selectedAvoid.length ? [`忌口：${rStore.selectedAvoid.join('、')}`] : []),
].join(' · '))

watch([matches, () => rStore.toolRecipes], () => {
  limit.value = 8
})

function emoji(name: string) {
  return allFood.value.find(item => item.name === name)?.emoji || '🥣'
}

function togglePantry(name: string) {
  pantry.value = pantry.value.includes(name) ? pantry.value.filter(item => item !== name) : [...pantry.value, name]
}

function example() {
  rStore.addStuff('番茄、鸡蛋')
  rStore.curTool = '一口大锅'
}

function goToResults() {
  resultsHeading.value?.scrollIntoView({ behavior: reducedMotion.value === 'reduce' ? 'instant' : 'smooth', block: 'start' })
  resultsHeading.value?.focus({ preventScroll: true })
}

function startDrag(event: DragEvent, item: StuffItem) {
  if (!event.dataTransfer)
    return
  event.dataTransfer.setData(dragType, item.name)
  event.dataTransfer.effectAllowed = 'copy'
}

function dragOver(event: DragEvent) {
  if (!event.dataTransfer?.types.includes(dragType))
    return
  event.preventDefault()
  event.dataTransfer.dropEffect = 'copy'
  dragActive.value = true
}

function dropIngredient(event: DragEvent) {
  dragActive.value = false
  const name = event.dataTransfer?.getData(dragType)
  if (!name || !acceptsIngredient(name))
    return
  event.preventDefault()
  // Dropping an already selected ingredient must not toggle it off.
  rStore.addStuff(name)
}

function addUnlisted() {
  const name = query.value.trim()
  if (name) {
    rStore.addStuff(name)
    query.value = ''
  }
}
</script>

<template>
  <div class="cooking-workbench" :class="{ 'is-desktop': desktop }">
    <div class="wb-layout">
      <section class="wb-tray wb-panel" aria-labelledby="wb-tray-title">
        <h2 id="wb-tray-title">
          选择食材
        </h2>
        <label class="wb-search">
          <span>找食材</span>
          <input v-model="query" type="search" placeholder="如：土豆、鸡蛋、西红柿" autocomplete="off">
        </label>
        <div class="wb-categories" role="group" aria-label="食材分类">
          <button v-for="(group, index) in categories" :key="group.category" type="button" :aria-pressed="category === index && !query" @click="category = index; query = ''; expanded = false">
            {{ group.title }}
          </button>
        </div>
        <div class="wb-food-grid">
          <button
            v-for="item in visibleItems"
            :key="item.name"
            type="button"
            class="wb-food"
            :aria-pressed="rStore.selectedStuff.includes(item.name)"
            :draggable="pointerFine"
            @dragstart="startDrag($event, item)"
            @dragend="dragActive = false"
            @click="rStore.toggleStuff(item.name)"
          >
            <span class="wb-emoji" aria-hidden="true">{{ item.emoji }}</span>
            <span>{{ item.name }}</span>
            <span v-if="rStore.selectedStuff.includes(item.name)" class="wb-selected-mark" aria-hidden="true">✓</span>
          </button>
        </div>
        <button v-if="!desktop && !query && hasMore" class="wb-text-button" type="button" :aria-expanded="expanded" @click="expanded = !expanded">
          {{ expanded ? '收起更多食材 ↑' : '展开更多食材 ↓' }}
        </button>
        <div v-if="query && !visibleItems.length" class="wb-empty-search">
          <p>列表中没有「{{ query }}」，加入后只查找现有菜谱。</p>
          <button class="wb-button" type="button" @click="addUnlisted">
            加入这项食材
          </button>
        </div>
        <p class="wb-hint">
          点一下加入，再点一下移除。<span v-if="pointerFine">也可拖到已选食材中。</span>
        </p>
        <NuxtLink class="wb-text-button" to="/catalog">
          管理食材和厨具
        </NuxtLink>

        <details class="wb-settings" :open="desktop || pantryExpanded">
          <summary :role="desktop ? 'heading' : undefined" :aria-level="desktop ? 3 : undefined" :tabindex="desktop ? -1 : undefined" @click.prevent="!desktop && (pantryExpanded = !pantryExpanded)">
            家里现有的调料 <span>{{ pantry.length }} 种</span>
          </summary>
          <p class="wb-hint">
            选择这次可用的调料，单独保存于此视图。
          </p>
          <div class="wb-pills" role="group" aria-label="已确认有的调料">
            <button v-for="name in pantryOptions" :key="name" type="button" :aria-pressed="pantry.includes(name)" @click="togglePantry(name)">
              <span v-if="pantry.includes(name)" aria-hidden="true">✓ </span>{{ name }}
            </button>
          </div>
        </details>
        <p class="wb-hint wb-pantry-summary">
          {{ pantry.length ? `已确认调料：${pantry.join('、')}` : '调料尚未确认；不默认有油、盐。' }}
        </p>
        <details class="wb-settings" :open="desktop || filtersExpanded">
          <summary :role="desktop ? 'heading' : undefined" :aria-level="desktop ? 3 : undefined" :tabindex="desktop ? -1 : undefined" @click.prevent="!desktop && (filtersExpanded = !filtersExpanded)">
            做法、难度与忌口
          </summary>
          <p class="wb-hint">
            筛选条件在两种视图间同步。
          </p>
          <div class="wb-pills" role="group" aria-label="做法筛选">
            <button v-for="group in METHOD_GROUPS" :key="group.label" type="button" :aria-pressed="rStore.methodGroups.includes(group.label)" @click="rStore.toggleMethodGroup(group.label)">
              <span v-if="methodIcons[group.label]" class="wb-cooking-icon" :class="methodIcons[group.label]" aria-hidden="true" />
              {{ group.label }}
            </button>
          </div>
          <div class="wb-pills" role="group" aria-label="难度筛选">
            <button v-for="level in DIFFICULTY_LEVELS" :key="level" type="button" :aria-pressed="rStore.difficulty.includes(level)" @click="rStore.toggleDifficulty(level)">
              {{ level }}
            </button>
          </div>
          <label class="wb-checkbox"><input v-model="rStore.noSpicy" type="checkbox">不吃辣</label>
          <button type="button" class="wb-text-button" @click="avoidOpen = true">
            调整忌口
          </button>
        </details>
        <button class="wb-results-jump" type="button" @click="goToResults">
          查看推荐菜谱 · {{ count }} 道 <span aria-hidden="true">↓</span>
        </button>
      </section>

      <section class="wb-bench" aria-labelledby="wb-board-title">
        <div class="wb-board" :class="{ 'is-drag-over': dragActive }" @dragover="dragOver" @dragleave.self="dragActive = false" @drop="dropIngredient">
          <div class="wb-section-heading">
            <h2 id="wb-board-title">
              已选食材 <span>{{ rStore.selectedStuff.length }} 种</span>
            </h2>
          </div>
          <div class="wb-board-items">
            <button v-for="name in rStore.selectedStuff" :key="name" type="button" class="wb-board-item" :aria-label="`移除${name}`" @click="rStore.toggleStuff(name)">
              <span class="wb-remove" aria-hidden="true">×</span>
              <span class="wb-emoji" aria-hidden="true">{{ emoji(name) }}</span>
              <span>{{ name }}</span>
            </button>
            <p v-if="!rStore.selectedStuff.length" class="wb-selection-empty">
              从食材列表中点选，或拖入食材。
            </p>
          </div>
          <p class="wb-board-note">
            点按已选食材即可移除。
          </p>
          <button v-if="!rStore.selectedStuff.length" class="wb-example" type="button" @click="example">
            试一试：番茄 + 鸡蛋 + 炒锅 <span aria-hidden="true">→</span>
          </button>
        </div>
        <div class="wb-tools wb-panel">
          <h2>厨具 <span>（可选）</span></h2>
          <div class="wb-pills" role="group" aria-label="工作台厨具">
            <button v-for="tool in tools" :key="tool.name" type="button" :aria-pressed="rStore.curTool === tool.name" @click="rStore.clickTool(tool)">
              <span v-if="toolIcons[tool.name] || tool.icon" class="wb-cooking-icon" :class="toolIcons[tool.name] || tool.icon" aria-hidden="true" /><span v-else aria-hidden="true">{{ tool.emoji }}</span>{{ tool.name === '一口大锅' ? '炒锅' : tool.name }}<span v-if="rStore.curTool === tool.name" aria-hidden="true">✓</span>
            </button>
          </div>
        </div>
        <button class="wb-results-jump" type="button" @click="goToResults">
          查看推荐菜谱 · {{ count }} 道 <span aria-hidden="true">↓</span>
        </button>
      </section>

      <section class="wb-results wb-panel" aria-labelledby="wb-results-title" :aria-busy="rStore.isSearching">
        <div class="wb-section-heading">
          <h2 id="wb-results-title" ref="resultsHeading" tabindex="-1">
            推荐菜谱
          </h2>
          <p role="status" aria-live="polite" aria-atomic="true">
            {{ rStore.isSearching ? '正在读取菜谱…' : rStore.loadError ? '菜谱加载失败' : `已选 ${rStore.selectedStuff.length} 种食材 · ${count} 道相关菜谱` }}
          </p>
        </div>
        <p class="wb-hint">
          根据已记录的食材匹配，完整用料请查看做法。
        </p>
        <div class="wb-result-filters">
          <label class="wb-search"><span>在结果里找菜名</span><input v-model="rStore.keyword" type="search" placeholder="输入菜名"></label>
          <label v-if="!toolOnly" class="wb-search"><span>匹配范围</span><select v-model="rStore.curMode"><option value="loose">全部推荐</option><option value="strict">用上所有已选</option><option value="survival">只看已记录食材齐全</option></select></label>
        </div>
        <p v-if="preferenceSummary" class="wb-hint">
          当前筛选：{{ preferenceSummary }}
        </p>
        <div v-if="rStore.loadError" class="wb-empty" role="alert">
          <h3>菜谱暂时没有加载成功</h3><button type="button" class="wb-button" @click="rStore.loadRecipes">
            重新加载菜谱
          </button>
        </div>
        <div v-else-if="rStore.isSearching" class="wb-empty">
          <p>正在准备菜谱，请稍候。</p>
        </div>
        <div v-else-if="!rStore.selectedStuff.length && !rStore.curTool" class="wb-empty">
          <h3>先选几样食材</h3><p>选食材后，相关菜谱会出现在这里。</p><button type="button" class="wb-button" @click="example">
            加入番茄、鸡蛋和炒锅
          </button>
        </div>
        <div v-else-if="!count" class="wb-empty">
          <h3>这组条件下暂时没有菜谱</h3><p>试试减少食材、取消厨具或放宽筛选。</p><button type="button" class="wb-button" @click="rStore.clearFilters">
            放宽筛选，保留食材
          </button>
        </div>
        <div v-else class="wb-recipe-list">
          <template v-if="toolOnly">
            <WorkbenchRecipeCard v-for="(recipe, index) in rStore.toolRecipes.slice(0, limit)" :key="index" :recipe="recipe" @detail="(recipe, match) => $emit('detail', recipe, match)" />
          </template>
          <template v-else>
            <WorkbenchRecipeCard v-for="match in matches.slice(0, limit)" :key="match.sourceIndex" :recipe="match.recipe" :match="match" @detail="(recipe, match) => $emit('detail', recipe, match)" />
          </template>
          <button v-if="count > limit" class="wb-button wb-more" type="button" @click="limit += 8">
            再看 {{ Math.min(8, count - limit) }} 道（还有 {{ count - limit }} 道）
          </button>
        </div>
      </section>
    </div>
    <AvoidSheet v-model:open="avoidOpen" :selected="rStore.selectedAvoid" @toggle="rStore.toggleAvoid" @clear="rStore.clearAvoid" />
  </div>
</template>

<style scoped>
.cooking-workbench {
  --wb-bg: var(--ion-background-color, #f2f2f7);
  --wb-surface: var(--ion-item-background, #fff);
  --wb-text: var(--ion-text-color, #1c1c1e);
  --wb-muted: var(--ion-text-color-step-400, #636366);
  --wb-line: var(--ion-item-border-color, #d1d1d6);
  --wb-green: var(--ion-color-primary);
  --wb-accent: var(--ion-color-primary);
  --wb-tint: var(--cook-fill);
  --wb-action-text: var(--ion-color-primary-contrast);
  max-width: 1180px;
  margin: 0 auto;
  padding: 4px 24px 32px;
  color: var(--wb-text);
  font-size: 16px;
  line-height: 1.5;
}
.wb-layout {
  display: grid;
  grid-template-columns: minmax(320px, 0.9fr) minmax(0, 1.5fr);
  grid-template-areas: 'tray bench' 'tray results';
  align-items: start;
  gap: 20px;
}
.wb-panel,
.wb-board {
  padding: 20px;
  border-radius: 12px;
  background: var(--wb-surface);
}
.wb-tray {
  grid-area: tray;
}
.wb-bench {
  grid-area: bench;
  min-width: 0;
}
.wb-results {
  grid-area: results;
  min-width: 0;
}
.cooking-workbench h2 {
  margin: 0 0 16px;
  font-size: 18px;
  font-weight: 600;
}
.cooking-workbench h2 > span {
  margin-left: 6px;
  font-size: 14px;
  font-weight: 400;
  color: var(--wb-muted);
}
.wb-search {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  font-size: 13px;
  color: var(--wb-muted);
}
.wb-search input,
.wb-search select {
  width: 100%;
  min-height: 44px;
  padding: 10px 12px;
  border: 0;
  border-radius: 8px;
  background: var(--cook-fill);
  color: var(--wb-text);
  font-size: 16px;
}
.wb-categories {
  display: flex;
  padding: 3px;
  margin: 16px 0;
  border-radius: 9px;
  background: var(--cook-fill);
}
.wb-categories button {
  flex: 1;
  min-height: 44px;
  padding: 4px;
  border-radius: 7px;
  font-size: 14px;
}
.wb-categories button[aria-pressed='true'] {
  background: var(--wb-surface);
  font-weight: 600;
  box-shadow: 0 1px 3px #00000014;
}
.wb-food-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(88px, 1fr));
  gap: 8px;
}
.wb-food {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 44px;
  padding: 8px;
  border-radius: 10px;
  background: var(--cook-fill);
  font-size: 14px;
  overflow-wrap: anywhere;
}
.wb-food[aria-pressed='true'] {
  background: var(--wb-accent);
  color: var(--wb-action-text);
}
.wb-emoji {
  font-size: 18px;
  line-height: 1.2;
}
.wb-selected-mark {
  font-size: 12px;
}
.wb-text-button {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  padding: 8px 0;
  color: var(--wb-accent);
  font-size: 15px;
}
.wb-hint,
.wb-board-note,
.wb-selection-empty {
  margin: 12px 0;
  color: var(--wb-muted);
  font-size: 13px;
  line-height: 1.6;
}
.wb-settings {
  border-top: 1px solid var(--wb-line);
  margin-top: 12px;
}
.wb-settings summary {
  cursor: pointer;
  padding: 12px 0;
  min-height: 44px;
}
.wb-settings summary > span {
  float: right;
  color: var(--wb-muted);
}
.is-desktop .wb-settings summary {
  cursor: default;
  list-style: none;
  font-weight: 600;
}
.is-desktop .wb-settings summary::-webkit-details-marker {
  display: none;
}
.wb-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.wb-pills + .wb-pills {
  margin-top: 12px;
}
.wb-pills button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 44px;
  padding: 9px 12px;
  border-radius: 10px;
  background: var(--cook-fill);
  color: var(--wb-text);
  font-size: 14px;
}
.wb-pills button[aria-pressed='true'] {
  background: var(--wb-accent);
  color: var(--wb-action-text);
}
.wb-pills button > span[class] {
  font-size: 18px;
}
.wb-cooking-icon {
  flex: none;
  width: 20px;
  height: 20px;
}
.wb-checkbox {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  margin-right: 16px;
}
.wb-checkbox input {
  width: 20px;
  height: 20px;
  accent-color: var(--wb-accent);
}
.wb-board.is-drag-over {
  outline: 2px solid var(--wb-accent);
  outline-offset: -2px;
}
.wb-section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 12px;
}
.wb-section-heading h2 {
  margin-bottom: 8px;
}
.wb-section-heading > p {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--wb-muted);
}
.wb-board-items {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.wb-board-item {
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 44px;
  max-width: 100%;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--cook-fill);
  overflow-wrap: anywhere;
}
.wb-remove {
  order: 1;
  margin-left: 4px;
  color: var(--wb-muted);
  font-size: 20px;
}
.wb-example {
  min-height: 44px;
  color: var(--wb-accent);
  font-size: 15px;
  text-align: left;
}
.wb-tools {
  margin-top: 12px;
}
.wb-results-jump {
  display: none;
}
.wb-result-filters {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
  margin: 16px 0;
}
.wb-empty {
  padding: 28px 8px;
  text-align: center;
}
.wb-empty h3 {
  margin: 8px 0;
  font-size: 20px;
  font-weight: 600;
}
.wb-empty p,
.wb-empty-search p {
  color: var(--wb-muted);
  font-size: 15px;
  line-height: 1.6;
}
.wb-button {
  min-height: 44px;
  margin-top: 12px;
  padding: 10px 16px;
  border-radius: 10px;
  background: var(--wb-accent);
  color: var(--wb-action-text);
  font-size: 15px;
  font-weight: 500;
}
.wb-more {
  display: block;
  margin: 16px auto 0;
}
.cooking-workbench :is(button, input, select, summary, h2, a):focus-visible {
  outline: 2px solid var(--wb-accent);
  outline-offset: 3px;
}
@media (hover: hover) and (pointer: fine) {
  .wb-food:hover,
  .wb-pills button:hover {
    filter: brightness(0.95);
  }
}
@media (max-width: 760px) {
  .cooking-workbench {
    padding: 0 16px 24px;
  }
  .wb-layout {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas: 'bench' 'tray' 'results';
    gap: 16px;
  }
  .wb-panel,
  .wb-board {
    padding: 16px;
  }
  .wb-results-jump {
    display: flex;
    align-items: center;
    justify-content: space-between;
    width: 100%;
    min-height: 44px;
    margin-top: 8px;
    color: var(--wb-accent);
    font-size: 15px;
  }
  .wb-result-filters {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
