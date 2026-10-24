<script setup lang="ts">
import type { RecipeMatch } from '@yunyoujun/cook'
import type { RecipeItem } from '~/types'
import { uniqueStuff } from '@yunyoujun/cook/recommend'

const props = defineProps<{ recipe: RecipeItem, match?: RecipeMatch }>()
defineEmits<{ detail: [recipe: RecipeItem, match?: RecipeMatch] }>()
const recorded = computed(() => uniqueStuff(props.recipe.stuff))
const tools = computed(() => props.recipe.tools.map(tool => tool === '一口大锅' ? '炒锅' : tool).join(' / '))
</script>

<template>
  <article class="wb-recipe">
    <div class="wb-recipe-heading">
      <h3>{{ recipe.name }}</h3>
      <span>{{ tools || '厨具未记录' }}</span>
    </div>
    <div class="wb-recipe-body">
      <div class="wb-recipe-ingredients">
        <template v-if="match">
          <p class="wb-matched">
            <span aria-hidden="true">✓</span> 已匹配：{{ match.matchedStuff.join('、') }}
          </p>
          <p v-if="match.pantryStuff.length" class="wb-matched">
            <span aria-hidden="true">✓</span> 已确认调料：{{ match.pantryStuff.join('、') }}
          </p>
          <p v-if="match.missingStuff.length" class="wb-missing">
            <span aria-hidden="true">＋</span> 还缺：{{ match.missingStuff.join('、') }}
          </p>
          <p v-else class="wb-matched">
            <span aria-hidden="true">✓</span> {{ match.optionalStuff.length ? '已匹配这份搭配中的已选食材' : '已记录食材齐全' }}
          </p>
          <p v-if="match.optionalStuff.length" class="wb-muted">
            可选加料：{{ match.optionalStuff.join('、') }}
          </p>
        </template>
        <p v-else class="wb-muted">
          {{ recorded.length ? `待核对：${recorded.join('、')}（尚未选择食材）` : '未知：菜谱未记录食材' }}
        </p>
        <p class="wb-muted wb-unknown">
          <span aria-hidden="true">?</span> 未知：未记录的材料、调料与用量，请查看做法确认。
        </p>
      </div>
      <button class="wb-action" type="button" :aria-label="`查看${recipe.name}做法`" @click="$emit('detail', recipe, match)">
        查看做法 <span aria-hidden="true">→</span>
      </button>
    </div>
  </article>
</template>

<style scoped>
.wb-recipe {
  padding: 20px;
  border: 1px solid var(--wb-line);
  border-radius: 14px;
  background: var(--wb-surface);
}
.wb-recipe-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-bottom: 12px;
}
.wb-recipe h3 {
  margin: 0;
  font-size: 19px;
  font-weight: 650;
  overflow-wrap: anywhere;
}
.wb-recipe-heading > span {
  color: var(--wb-muted);
  font-size: 13px;
}
.wb-recipe p {
  margin: 6px 0;
  font-size: 14px;
  line-height: 1.65;
}
.wb-matched {
  color: var(--wb-green);
}
.wb-missing {
  color: var(--wb-accent);
  font-weight: 600;
}
.wb-muted {
  color: var(--wb-muted);
}
.wb-unknown {
  padding-top: 6px;
}
.wb-action {
  display: inline-flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  margin-top: 12px;
  padding: 10px 16px;
  border-radius: 10px;
  color: var(--wb-action-text);
  background: var(--wb-green);
  font-size: 14px;
  font-weight: 600;
}
.wb-action:focus-visible {
  outline: 3px solid var(--wb-accent);
  outline-offset: 3px;
}
@media (min-width: 1000px) {
  .wb-recipe-body {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 16px;
    align-items: center;
  }
  .wb-action {
    margin-top: 0;
  }
}
</style>
