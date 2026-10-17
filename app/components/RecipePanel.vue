<script lang="ts" setup>
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import { recipePanelRef } from '~/composables/global'

const rStore = useRecipeStore()
const filtered = computed(() => Boolean(rStore.keyword.trim() || rStore.curTool || rStore.curMode !== 'loose'))
const filterSummary = computed(() => [
  rStore.curTool && `厨具：${rStore.curTool}`,
  rStore.keyword.trim() && `菜名：${rStore.keyword.trim()}`,
  rStore.curMode === 'strict' && '用上所有已选食材',
  rStore.curMode === 'survival' && '只看食材齐全',
].filter(Boolean).join('；'))
</script>

<template>
  <section ref="recipePanelRef" class="recipe-panel" aria-label="已有食材推荐">
    <h2 class="panel-title">
      🍲 这些食材，可以做什么？
    </h2>
    <p class="panel-note">
      仅核对菜谱已记录的食材；点菜名查看做法，核对用量与具体调味。
    </p>
    <details class="ranking-help">
      <summary>推荐怎么排？</summary>
      <p>优先食材齐全，再看还差一种、还差两种。同组里，用上已选食材更多的在前；相同时保留菜谱收录顺序。常备调料不额外加分。</p>
      <p>至少用上一种已选食材才会推荐。食材按现有分类合并别名并去重，例如西红柿计为番茄、鸡翅计为鸡肉。不会根据菜名补猜材料。</p>
    </details>

    <details class="recipe-filters">
      <summary class="filter-summary">
        筛选菜名和匹配范围{{ filtered ? '（已启用）' : '' }}
      </summary>
      <label class="filter-label" for="recipe-keyword">筛选菜名</label>
      <div class="keyword-row">
        <input id="recipe-keyword" v-model="rStore.keyword" class="keyword-input" type="search" placeholder="在推荐中找一道菜" autocomplete="off">
        <YlfButton v-if="rStore.keyword" type="button" variant="ghost" :round="false" class="filter-clear" @click="rStore.clearKeyWord">
          清除
        </YlfButton>
      </div>
      <label class="filter-label" for="recipe-mode">匹配范围</label>
      <select id="recipe-mode" v-model="rStore.curMode" class="mode-select">
        <option value="loose">
          推荐全部（最多差两种）
        </option>
        <option value="strict">
          用上所有已选食材
        </option>
        <option value="survival">
          只看食材齐全
        </option>
      </select>
    </details>
    <div v-if="filtered" class="active-filters">
      <span>{{ filterSummary }}</span>
      <YlfButton type="button" variant="ghost" :round="false" @click="rStore.clearFilters">
        清除筛选
      </YlfButton>
    </div>

    <div v-if="rStore.loadError" class="panel-empty" role="alert">
      <p>{{ rStore.loadError }}</p>
      <YlfButton type="button" variant="accent" appearance="outline" :round="false" class="empty-action" @click="rStore.loadRecipes">
        重新加载菜谱
      </YlfButton>
    </div>
    <p v-else-if="rStore.isSearching" class="panel-empty" role="status">
      正在准备本地菜谱…
    </p>
    <div v-else-if="!rStore.selectedStuff.length" class="panel-empty" role="status">
      <p class="empty-title">
        先往食材篮里放点东西吧
      </p>
      <p>选一两种已有食材，就能查看还需要准备什么。</p>
      <YlfButton type="button" variant="accent" appearance="outline" :round="false" class="empty-action" @click="rStore.addStuff('番茄'); rStore.addStuff('鸡蛋')">
        试试番茄 + 鸡蛋
      </YlfButton>
    </div>
    <template v-else>
      <div v-if="!rStore.recipeMatches.length" class="panel-empty" role="status">
        <p class="empty-title">
          暂时没有符合条件的菜谱
        </p>
        <p>只推荐用到已选食材、且最多差两种材料的菜谱。可以补充食材{{ filtered ? '，或清除筛选再试' : '，也可以换个组合' }}。</p>
        <YlfButton v-if="filtered" type="button" variant="accent" appearance="outline" :round="false" class="empty-action" @click="rStore.clearFilters">
          清除筛选，保留食材
        </YlfButton>
      </div>
      <RecipeMatchResults :matches="rStore.recipeMatches" />
    </template>
  </section>
</template>

<style scoped>
.recipe-panel {
  margin: var(--ylf-space-4) var(--ylf-space-3) 0;
  padding: var(--ylf-space-4) var(--ylf-space-3);
  text-align: left;
  border-top: 3px solid var(--cook-match-accent);
  scroll-margin-top: var(--ylf-space-3);
}
.panel-title {
  font-size: var(--ylf-text-lg);
  font-weight: 700;
  line-height: 1.5;
}
.panel-note {
  margin-top: var(--ylf-space-1);
  color: var(--cook-match-muted);
  font-size: var(--cook-match-font-secondary);
  line-height: 1.6;
}
.ranking-help {
  margin: var(--ylf-space-1) 0;
  color: var(--cook-match-muted);
  font-size: var(--cook-match-font-secondary);
  line-height: 1.6;
}
.ranking-help summary {
  font-size: var(--cook-match-font-primary);
  color: var(--cook-match-accent);
  min-height: var(--cook-match-control-height);
  display: flex;
  align-items: center;
  cursor: pointer;
}
.ranking-help summary::after {
  content: ' ＋';
}
.ranking-help[open] summary::after {
  content: ' −';
}
.ranking-help p {
  margin-bottom: var(--ylf-space-2);
}
.recipe-filters {
  margin: var(--ylf-space-1) 0 var(--ylf-space-3);
}
.filter-summary {
  display: flex;
  align-items: center;
  cursor: pointer;
  min-height: var(--cook-match-control-height);
  font-size: var(--cook-match-font-primary);
  color: var(--cook-match-accent);
}
.filter-summary::after {
  content: ' ＋';
}
.recipe-filters[open] .filter-summary::after {
  content: ' −';
}
.active-filters span {
  overflow-wrap: anywhere;
}
.filter-label {
  display: block;
  font-size: var(--cook-match-font-primary);
  color: var(--cook-match-muted);
  margin: var(--ylf-space-2) 0 var(--ylf-space-2);
}
.keyword-row {
  display: flex;
  gap: var(--ylf-space-2);
}
.keyword-input,
.mode-select {
  min-width: 0;
  min-height: var(--cook-match-control-height);
  padding: var(--ylf-space-2) var(--ylf-space-3);
  border: 1px solid var(--cook-match-border);
  border-radius: var(--ylf-radius-sm);
  background: var(--cook-match-input);
  color: var(--cook-match-text);
  font-size: var(--cook-match-font-input);
}
.keyword-input {
  flex: 1;
  width: 100%;
}
.mode-select {
  width: 100%;
}
.filter-clear {
  min-width: var(--cook-match-control-height);
  color: var(--cook-match-accent);
}
.active-filters {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: var(--cook-match-font-primary);
  color: var(--cook-match-muted);
  gap: var(--ylf-space-3);
}
.active-filters button {
  flex-shrink: 0;
  color: var(--cook-match-accent);
  min-height: var(--cook-match-control-height);
}
.panel-empty {
  padding: var(--ylf-space-6) 0;
  font-size: var(--cook-match-font-primary);
  line-height: 1.8;
  color: var(--cook-match-muted);
}
.empty-title {
  font-size: var(--cook-match-font-title);
  font-weight: 600;
  color: var(--cook-match-text);
  margin-bottom: var(--ylf-space-2);
}
.empty-action {
  min-height: var(--cook-match-control-height);
  margin-top: var(--ylf-space-3);
  padding: var(--ylf-space-2) var(--ylf-space-3);
  border: 1px solid var(--cook-match-accent);
  border-radius: var(--ylf-radius-sm);
  color: var(--cook-match-accent);
}
</style>
