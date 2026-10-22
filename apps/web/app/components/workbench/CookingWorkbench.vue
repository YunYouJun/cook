<script setup lang="ts">
import type { RecipeMatch } from '@yunyoujun/cook'
import type { RecipeItem, StuffItem } from '~/types'
import { DIFFICULTY_LEVELS, METHOD_GROUPS } from '@yunyoujun/cook/recommend'
import { meat, staple, tools, vegetable } from '~/data/food'
import { pantryOptions } from '~/data/pantry'

defineEmits<{ detail: [recipe: RecipeItem, match?: RecipeMatch] }>()
const pantry = defineModel<string[]>('pantry', { required: true })
const rStore = useRecipeStore()
const pointerFine = useMediaQuery('(hover: hover) and (pointer: fine)')
const reducedMotion = usePreferredReducedMotion()
const categories = [
  { name: '蔬菜', items: vegetable },
  { name: '肉和蛋', items: meat },
  { name: '主食', items: staple },
]
const category = ref(0)
const query = ref('')
const expanded = ref(false)
const avoidOpen = ref(false)
const allFood = [...vegetable, ...meat, ...staple]
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
    return allFood.filter(item => item.name.includes(text) || item.alias?.includes(text))
  return categories[category.value]!.items.filter(item => expanded.value || !item.more || rStore.selectedStuff.includes(item.name))
})
const hasMore = computed(() => categories[category.value]!.items.some(item => item.more))
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
  return allFood.find(item => item.name === name)?.emoji || '🥣'
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
  if (!name || !allFood.some(item => item.name === name))
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
  <main class="cooking-workbench">
    <header class="wb-intro">
      <h1>料理工作台</h1>
      <p>把手边的食材放上来，发现有据可查的搭配。</p>
    </header>

    <div class="wb-layout">
      <section class="wb-tray wb-panel" aria-labelledby="wb-tray-title">
        <h2 id="wb-tray-title">
          食材托盘
        </h2>
        <label class="wb-search">
          <span>找食材</span>
          <input v-model="query" type="search" placeholder="如：土豆、鸡蛋、西红柿" autocomplete="off">
        </label>
        <div class="wb-categories" role="group" aria-label="食材分类">
          <button v-for="(group, index) in categories" :key="group.name" type="button" :aria-pressed="category === index && !query" @click="category = index; query = ''; expanded = false">
            {{ group.name }}
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
        <button v-if="!query && hasMore" class="wb-text-button" type="button" :aria-expanded="expanded" @click="expanded = !expanded">
          {{ expanded ? '收起更多食材 ↑' : '展开更多食材 ↓' }}
        </button>
        <div v-if="query && !visibleItems.length" class="wb-empty-search">
          <p>托盘暂未收录「{{ query }}」，加入后只查找现有菜谱。</p>
          <button class="wb-button" type="button" @click="addUnlisted">
            加入这项食材
          </button>
        </div>
        <p class="wb-hint">
          点一下加入，再点一下移除。<span v-if="pointerFine">也可拖到砧板上。</span>
        </p>

        <details class="wb-settings">
          <summary>本次确认的调料 <span>{{ pantry.length }} 种</span></summary>
          <p class="wb-hint">
            油、盐也需要你确认。这里单独记录，不沿用经典模式的默认常备。
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
        <details class="wb-settings">
          <summary>做法、难度与忌口</summary>
          <p class="wb-hint">
            这些筛选与经典模式同步。
          </p>
          <div class="wb-pills" role="group" aria-label="做法筛选">
            <button v-for="group in METHOD_GROUPS" :key="group.label" type="button" :aria-pressed="rStore.methodGroups.includes(group.label)" @click="rStore.toggleMethodGroup(group.label)">
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
          查看菜谱发现 · {{ count }} 道 <span aria-hidden="true">↓</span>
        </button>
      </section>

      <section class="wb-bench" aria-labelledby="wb-board-title">
        <div class="wb-board" :class="{ 'is-drag-over': dragActive }" @dragover="dragOver" @dragleave.self="dragActive = false" @drop="dropIngredient">
          <div class="wb-section-heading">
            <h2 id="wb-board-title">
              砧板 <span>· 已选 {{ rStore.selectedStuff.length }} 种</span>
            </h2>
          </div>
          <div class="wb-board-items">
            <button v-for="name in rStore.selectedStuff" :key="name" type="button" class="wb-board-item" :aria-label="`从砧板移除${name}`" @click="rStore.toggleStuff(name)">
              <span class="wb-remove" aria-hidden="true">×</span>
              <span class="wb-emoji" aria-hidden="true">{{ emoji(name) }}</span>
              <span>{{ name }}</span>
            </button>
            <div class="wb-board-placeholder" aria-hidden="true">
              <span>＋</span>{{ rStore.selectedStuff.length ? '继续加入食材' : '从食材托盘点选' }}
            </div>
          </div>
          <p class="wb-board-note">
            不用记摆放顺序，点选就能发现搭配。
          </p>
          <button v-if="!rStore.selectedStuff.length" class="wb-example" type="button" @click="example">
            试一试：番茄 + 鸡蛋 + 炒锅 <span aria-hidden="true">→</span>
          </button>
        </div>
        <div class="wb-tools wb-panel">
          <h2>厨具 <span>（可选）</span></h2>
          <div class="wb-pills" role="group" aria-label="工作台厨具">
            <button v-for="tool in tools" :key="tool.name" type="button" :aria-pressed="rStore.curTool === tool.name" @click="rStore.clickTool(tool)">
              <span :class="tool.icon" aria-hidden="true" />{{ tool.name === '一口大锅' ? '炒锅' : tool.name }}<span v-if="rStore.curTool === tool.name" aria-hidden="true">✓</span>
            </button>
          </div>
        </div>
        <button class="wb-results-jump" type="button" @click="goToResults">
          查看菜谱发现 · {{ count }} 道 <span aria-hidden="true">↓</span>
        </button>
      </section>

      <section class="wb-results wb-panel" aria-labelledby="wb-results-title" :aria-busy="rStore.isSearching">
        <div class="wb-section-heading">
          <h2 id="wb-results-title" ref="resultsHeading" tabindex="-1">
            菜谱发现
          </h2>
          <p role="status" aria-live="polite" aria-atomic="true">
            {{ rStore.isSearching ? '正在读取菜谱…' : rStore.loadError ? '菜谱加载失败' : `已选 ${rStore.selectedStuff.length} 种食材 · ${count} 道相关菜谱` }}
          </p>
        </div>
        <p class="wb-hint">
          来自现有菜谱，食材摆放顺序不影响结果。只核对已记录的食材，不代表全部用料已齐备。
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
          <span class="wb-empty-emoji" aria-hidden="true">🍽️</span><h3>今天，从手边的食材开始</h3><p>选食材后，相关菜谱会出现在这里。</p><button type="button" class="wb-button" @click="example">
            加入番茄、鸡蛋和炒锅
          </button>
        </div>
        <div v-else-if="!count" class="wb-empty">
          <h3>这组条件下暂时没有菜谱</h3><p>没有匹配也很正常。试试调整食材、取消厨具或放宽筛选；这里不会生成未经验证的配方。</p><button type="button" class="wb-button" @click="rStore.clearFilters">
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
  </main>
</template>

<style scoped>
.cooking-workbench {
  --wb-bg: #faf8f3;
  --wb-surface: #fff;
  --wb-text: #223e35;
  --wb-muted: #626b66;
  --wb-line: #e3e5de;
  --wb-green: #244e40;
  --wb-accent: #a64128;
  --wb-tint: #f7eee7;
  --wb-action-text: #fff;
  color: var(--wb-text);
  max-width: 1180px;
  margin: 0 auto;
  padding: 6px 24px 40px;
  font-size: 15px;
  line-height: 1.5;
}
.wb-intro {
  text-align: center;
  margin-bottom: 28px;
}
.wb-intro h1 {
  margin: 0 0 8px;
  font-size: clamp(28px, 3vw, 36px);
  font-weight: 750;
  letter-spacing: 0.02em;
}
.wb-intro p {
  margin: 0;
  color: var(--wb-muted);
  font-size: 16px;
}
.wb-layout {
  display: grid;
  grid-template-columns: minmax(300px, 0.85fr) minmax(0, 1.5fr);
  grid-template-areas: 'tray bench' 'tray results';
  align-items: start;
  gap: 18px;
}
.wb-panel {
  padding: 22px;
  border: 1px solid var(--wb-line);
  border-radius: 18px;
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
  font-size: 21px;
  font-weight: 700;
  margin: 0 0 16px;
}
.cooking-workbench h2 > span {
  font-size: 14px;
  font-weight: 500;
}
.wb-search {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  color: var(--wb-muted);
  min-width: 0;
}
.wb-search input,
.wb-search select {
  width: 100%;
  min-height: 44px;
  padding: 10px 12px;
  border: 1px solid var(--wb-line);
  border-radius: 10px;
  background: var(--wb-bg);
  color: var(--wb-text);
  font-size: 16px;
}
.wb-categories {
  display: flex;
  border-bottom: 1px solid var(--wb-line);
  margin: 14px 0;
}
.wb-categories button {
  flex: 1;
  min-height: 44px;
  border-bottom: 2px solid transparent;
  font-weight: 600;
}
.wb-categories button[aria-pressed='true'] {
  color: var(--wb-accent);
  border-bottom-color: var(--wb-accent);
}
.wb-food-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
.wb-food {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 10px 2px;
  min-height: 88px;
  border: 1px solid var(--wb-line);
  border-radius: 12px;
  background: var(--wb-bg);
  font-size: 14px;
  transition:
    transform 150ms ease,
    border-color 150ms ease;
}
.wb-food[aria-pressed='true'] {
  color: var(--wb-accent);
  border-color: var(--wb-accent);
  background: var(--wb-tint);
  font-weight: 600;
}
.wb-emoji {
  display: block;
  font-size: 30px;
  line-height: 1.2;
}
.wb-selected-mark {
  position: absolute;
  right: 3px;
  top: 3px;
  font-size: 11px;
  background: var(--wb-accent);
  color: var(--wb-surface);
  border-radius: 50%;
  width: 16px;
  height: 16px;
}
.wb-text-button {
  min-height: 44px;
  padding: 8px 0;
  color: var(--wb-accent);
  font-size: 14px;
}
.wb-hint {
  color: var(--wb-muted);
  margin: 12px 0;
  font-size: 13px;
  line-height: 1.7;
}
.wb-settings {
  border-top: 1px solid var(--wb-line);
  padding: 12px 0 0;
  margin-top: 10px;
}
.wb-settings summary {
  cursor: pointer;
  padding: 10px 0;
  min-height: 44px;
  font-weight: 600;
}
.wb-settings summary > span {
  float: right;
  color: var(--wb-muted);
  font-weight: 400;
}
.wb-pills {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
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
  border: 1px solid var(--wb-line);
  border-radius: 9px;
  padding: 9px 12px;
  font-size: 14px;
  color: var(--wb-text);
  background: var(--wb-surface);
}
.wb-pills button[aria-pressed='true'] {
  border-color: var(--wb-green);
  color: var(--wb-green);
  background: var(--wb-bg);
  box-shadow: inset 0 0 0 1px var(--wb-green);
  font-weight: 600;
}
.wb-pills button > span[class] {
  font-size: 20px;
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
  accent-color: var(--wb-green);
}
.wb-board {
  padding: 22px;
  border: 8px solid #dcb993;
  border-radius: 18px;
  background: repeating-linear-gradient(2deg, transparent 0 8px, #e3bc8c18 8px 10px), #f5dec0;
  color: #244638;
  box-shadow: inset 0 0 0 1px #cba16e;
  transition: border-color 150ms ease;
}
.wb-board.is-drag-over {
  border-color: #244e40;
}
.wb-section-heading {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 12px;
}
.wb-section-heading h2 {
  margin-bottom: 8px;
}
.wb-section-heading > p {
  font-size: 13px;
  color: var(--wb-muted);
  margin: 0 0 8px;
}
.wb-board-items {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 10px 0;
}
.wb-board-item {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  width: 100px;
  min-height: 110px;
  padding: 16px 8px 10px;
  border-radius: 14px;
  background: #fffcf7;
  color: #244638;
  box-shadow: 0 4px 8px #7e583a12;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.wb-board-item .wb-emoji {
  font-size: 36px;
}
.wb-remove {
  position: absolute;
  right: 8px;
  top: 4px;
  font-size: 18px;
  font-weight: 400;
  color: #65655a;
}
.wb-board-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 110px;
  width: 110px;
  border: 1px dashed #ad8c65;
  border-radius: 14px;
  color: #6c573c;
  font-size: 13px;
}
.wb-board-placeholder > span {
  font-size: 30px;
}
.wb-board-note {
  margin: 8px 0 0;
  color: #6c573c;
  font-size: 13px;
}
.wb-example {
  min-height: 44px;
  margin-top: 10px;
  text-align: left;
  font-size: 14px;
  font-weight: 600;
}
.wb-tools {
  padding: 18px;
  margin-top: 10px;
}
.wb-tools h2 {
  font-size: 16px;
  margin-bottom: 12px;
}
.wb-tools .wb-pills button {
  flex: 1 0 auto;
  padding-inline: 9px;
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
.wb-recipe-list {
  display: grid;
  gap: 12px;
}
.wb-empty {
  padding: 24px 4px;
  text-align: center;
}
.wb-empty h3 {
  margin: 8px 0;
  font-size: 18px;
  font-weight: 650;
}
.wb-empty p,
.wb-empty-search p {
  font-size: 14px;
  color: var(--wb-muted);
  line-height: 1.8;
}
.wb-empty-emoji {
  font-size: 40px;
}
.wb-button {
  min-height: 44px;
  padding: 10px 16px;
  border-radius: 10px;
  background: var(--wb-green);
  color: var(--wb-action-text);
  font-size: 14px;
  font-weight: 600;
  margin-top: 12px;
}
.wb-more {
  justify-self: center;
}
.cooking-workbench :is(button, input, select, summary, h2):focus-visible {
  outline: 3px solid var(--wb-accent);
  outline-offset: 3px;
}
@media (hover: hover) and (pointer: fine) {
  .wb-food:hover {
    transform: translateY(-2px);
    border-color: var(--wb-accent);
  }
}
@media (min-width: 1000px) {
  .wb-results {
    display: grid;
    grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
    column-gap: 12px;
  }
  .wb-results > .wb-section-heading {
    grid-column: 1;
    grid-row: 1;
    flex-direction: column;
    align-items: flex-start;
    gap: 0;
  }
  .wb-result-filters {
    grid-column: 2;
    grid-row: 1;
    align-self: start;
    margin: 0;
    gap: 8px;
  }
  .wb-result-filters .wb-search span {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  .wb-results > :not(.wb-section-heading, .wb-result-filters) {
    grid-column: 1 / -1;
  }
}
@media (max-width: 760px) {
  .cooking-workbench {
    padding: 4px 14px 28px;
  }
  .wb-intro {
    margin-bottom: 20px;
  }
  .wb-intro p {
    font-size: 14px;
  }
  .wb-layout {
    grid-template-columns: minmax(0, 1fr);
    grid-template-areas: 'bench' 'tray' 'results';
    gap: 16px;
  }
  .wb-panel {
    padding: 18px;
  }
  .wb-board {
    padding: 16px;
    border-width: 6px;
  }
  .wb-board-item,
  .wb-board-placeholder {
    width: 86px;
    min-height: 96px;
  }
  .wb-board-item .wb-emoji {
    font-size: 30px;
  }
  .wb-results-jump {
    display: flex;
    justify-content: space-between;
    width: 100%;
    min-height: 44px;
    padding: 12px 4px 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--wb-green);
  }
  .wb-result-filters {
    grid-template-columns: minmax(0, 1fr);
  }
}
:global(.ion-palette-dark .cooking-workbench) {
  --wb-bg: #1e2722;
  --wb-surface: #18201c;
  --wb-text: #edf4ed;
  --wb-muted: #b1beb4;
  --wb-line: #3b4940;
  --wb-green: #a7d4b9;
  --wb-accent: #ffb192;
  --wb-tint: #3a2e26;
  --wb-action-text: #18201c;
}
@media (prefers-reduced-motion: reduce) {
  .wb-food,
  .wb-board {
    transition: none;
  }
  .wb-food:hover {
    transform: none;
  }
}
</style>
