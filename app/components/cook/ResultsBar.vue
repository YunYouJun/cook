<script lang="ts" setup>
/**
 * 停在标签栏上方的结果预览条：实时显示能做几道菜，点击展开结果弹层
 */
const emit = defineEmits<{ open: [] }>()
const rStore = useRecipeStore()

const groupCounts = computed(() => [0, 1, 2].map(missing => rStore.recipeMatches.filter(match => match.missingStuff.length === missing).length))
const hasSelection = computed(() => rStore.selectedStuff.length > 0 || Boolean(rStore.curTool))
const count = computed(() => rStore.resultCount)

// 数字变大从下往上滚，变小从上往下滚
const rollDirection = ref(1)
watch(count, (value, oldValue) => {
  rollDirection.value = value >= oldValue ? 1 : -1
})

const subtitle = computed(() => {
  if (!rStore.selectedStuff.length)
    return rStore.curTool ? '只按厨具筛选，再选几样食材会更准' : '看看家里的食材能做什么'
  const [full, one, two] = groupCounts.value
  return `齐全 ${full} · 差一种 ${one} · 差两种 ${two}`
})

function tryExample() {
  rStore.addStuff('番茄')
  rStore.addStuff('鸡蛋')
}
</script>

<template>
  <ion-footer :translucent="true" class="results-bar-footer">
    <ion-toolbar>
      <div class="results-bar">
        <template v-if="rStore.loadError">
          <div class="results-bar-text">
            <div class="results-bar-title">
              菜谱没有加载成功
            </div>
            <div class="results-bar-sub">
              {{ rStore.loadError }}
            </div>
          </div>
          <ion-button shape="round" size="small" @click="rStore.loadRecipes">
            重试
          </ion-button>
        </template>
        <template v-else-if="!hasSelection">
          <div class="results-bar-text">
            <div class="results-bar-title">
              先选几样食材
            </div>
            <div class="results-bar-sub">
              {{ subtitle }}
            </div>
          </div>
          <ion-button shape="round" size="small" fill="outline" @click="tryExample">
            试试番茄＋鸡蛋
          </ion-button>
        </template>
        <button v-else type="button" class="results-bar-hit" :disabled="rStore.isSearching" @click="emit('open')">
          <span class="results-bar-text">
            <span class="results-bar-title">
              <template v-if="rStore.isSearching">正在准备菜谱…</template>
              <template v-else>
                {{ rStore.selectedStuff.length ? '可以做' : `用${rStore.curTool}能做` }}
                <span class="results-bar-count" :style="{ '--roll': rollDirection }">
                  <Transition name="count-roll" mode="out-in">
                    <span :key="count" class="results-bar-count-value">{{ count }}</span>
                  </Transition>
                </span>
                道菜
              </template>
            </span>
            <span class="results-bar-sub">{{ subtitle }}</span>
          </span>
          <span class="results-bar-go" data-flight-target>
            查看<ion-icon :icon="ioniconsChevronUp" aria-hidden="true" />
          </span>
        </button>
      </div>
    </ion-toolbar>
  </ion-footer>
</template>

<style scoped>
.results-bar-footer ion-toolbar {
  --padding-start: 16px;
  --padding-end: 16px;
  --padding-top: 8px;
  --padding-bottom: 8px;
}
.results-bar,
.results-bar-hit {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 44px;
  text-align: start;
}
.results-bar-text {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}
.results-bar-title {
  font-size: 17px;
  font-weight: 600;
  line-height: 22px;
  color: var(--ion-text-color, #000);
}
.results-bar-sub {
  font-size: 13px;
  line-height: 18px;
  color: var(--ion-color-medium);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.results-bar-go {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 2px;
  height: 34px;
  padding: 0 12px 0 14px;
  border-radius: 17px;
  background: var(--ion-color-primary);
  color: var(--ion-color-primary-contrast);
  font-size: 15px;
  font-weight: 600;
  transition: transform 0.2s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.results-bar-hit:active .results-bar-go {
  transform: scale(0.94);
}
.results-bar-hit:disabled {
  opacity: 0.6;
}
.results-bar-count {
  display: inline-flex;
  overflow: hidden;
  vertical-align: bottom;
}
.results-bar-count-value {
  display: inline-block;
  min-width: 1ch;
  font-variant-numeric: tabular-nums;
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
  .count-roll-enter-active,
  .count-roll-leave-active,
  .results-bar-go {
    transition: none;
  }
}
</style>
