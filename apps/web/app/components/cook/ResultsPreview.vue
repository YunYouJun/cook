<script setup lang="ts">
defineProps<{
  loading: boolean
  error: string
  hasSelection: boolean
  count: number
  summary: string
  emojis: string[]
  expanded: boolean
  docked?: boolean
}>()
defineEmits<{ toggle: [], adjust: [], example: [], retry: [] }>()
</script>

<template>
  <div class="results-preview" :class="{ 'is-docked': docked }">
    <template v-if="error">
      <div class="results-preview-copy">
        <strong>菜谱没有加载成功</strong><span>{{ error }}</span>
      </div>
      <button class="results-preview-action" type="button" @click.stop="$emit('retry')">
        重试
      </button>
    </template>
    <template v-else-if="!hasSelection">
      <div class="results-preview-copy">
        <strong>{{ docked ? '推荐菜谱' : '选好食材，看看能做什么' }}</strong><span>{{ docked ? '根据已选食材实时更新' : '厨具可选，不选也能找菜谱' }}</span>
      </div>
      <button class="results-preview-example" type="button" @click.stop="$emit('example')">
        试试番茄＋鸡蛋
      </button>
    </template>
    <template v-else>
      <button class="results-preview-avatars" type="button" aria-label="回到顶部调整食材" data-flight-target @click.stop="$emit('adjust')">
        <span v-for="(emoji, index) in emojis" :key="index" aria-hidden="true">{{ emoji }}</span>
      </button>
      <div v-if="docked" class="results-preview-copy" aria-live="polite">
        <strong>{{ loading ? '正在准备菜谱…' : count ? `可以做 ${count} 道菜` : '暂时没有匹配的菜' }}</strong>
        <span>{{ summary }}</span>
      </div>
      <button
        v-else
        class="results-preview-open"
        type="button"
        :aria-expanded="expanded"
        aria-controls="cook-results-body"
        :aria-label="expanded ? '收起菜谱' : loading ? '展开正在加载的菜谱' : count ? `查看 ${count} 道菜` : '查看筛选建议'"
        @click.stop="$emit('toggle')"
      >
        <span class="results-preview-copy">
          <strong>{{ loading ? '正在准备菜谱…' : count ? `可以做 ${count} 道菜` : '暂时没有匹配的菜' }}</strong>
          <span>{{ expanded ? summary : count || loading ? '点这里展开，选一道喜欢的菜' : '换个食材，或放宽筛选条件' }}</span>
        </span>
        <span class="results-preview-action">
          {{ expanded ? '收起' : count || loading ? '查看菜谱' : '调整筛选' }}
          <span class="results-preview-chevron" :class="{ expanded }" aria-hidden="true">⌃</span>
        </span>
      </button>
    </template>
  </div>
</template>

<style scoped>
.results-preview {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 60px;
}
.results-preview-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
  text-align: start;
}
.results-preview-copy strong {
  font-size: 16px;
  line-height: 22px;
  color: var(--ion-text-color, #000);
  font-weight: 650;
}
.results-preview-copy > span {
  overflow: hidden;
  color: var(--ion-color-medium);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.results-preview-open {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 8px;
  min-width: 0;
  min-height: 56px;
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
}
.results-preview-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  flex: none;
  min-height: 44px;
  border: 0;
  border-radius: 22px;
  padding: 0 14px;
  background: var(--ion-color-primary);
  color: var(--ion-color-primary-contrast, #fff);
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  cursor: pointer;
}
.results-preview-example {
  flex: none;
  min-height: 44px;
  border: 0;
  border-radius: 22px;
  padding: 0 12px;
  background: var(--cook-fill);
  color: var(--ion-color-primary);
  font-size: 12px;
  cursor: pointer;
}
.results-preview-chevron {
  font-size: 20px;
  line-height: 1;
  transform: translateY(3px);
}
.results-preview-chevron.expanded {
  transform: rotate(180deg) translateY(2px);
}
.results-preview-avatars {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  border: 0;
  padding: 0 0 0 4px;
  background: transparent;
  cursor: pointer;
}
.results-preview-avatars span {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 30px;
  margin-inline-start: -4px;
  border: 2px solid var(--ion-background-color, #f2f2f6);
  border-radius: 50%;
  background: var(--ion-item-background, #fff);
  font-size: 16px;
}
.results-preview button:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 3px;
  border-radius: 12px;
}
.results-preview.is-docked {
  min-height: 46px;
  flex-wrap: wrap;
}
.is-docked .results-preview-copy strong {
  font-size: 15px;
}
.is-docked .results-preview-example,
.is-docked .results-preview-action {
  min-height: 30px;
  border-radius: 6px;
  padding-inline: 9px;
  font-size: 12px;
}
.is-docked .results-preview-avatars {
  min-height: 34px;
}
@media (max-width: 359px) {
  .results-preview-avatars {
    display: none;
  }
  .results-preview-copy strong {
    font-size: 14px;
  }
}
</style>
