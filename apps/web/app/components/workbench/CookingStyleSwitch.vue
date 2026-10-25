<script setup lang="ts">
import type { CookingStyle } from '~/composables/useCookingStyle'

const model = defineModel<CookingStyle>({ required: true })
const pendingStyle = shallowRef<CookingStyle>()
const currentLabel = computed(() => model.value === 'classic' ? '分组选择' : '并排浏览')
const select = useTemplateRef<{ $el: HTMLIonSelectElement }>('select')

// 切换布局会重建标题栏，等菜单关闭后再更新，避免打断关闭动画。
function applySelection() {
  if (pendingStyle.value) {
    model.value = pendingStyle.value
    pendingStyle.value = undefined
  }
}

function focus() {
  // Ionic 的 Shadow DOM 在 Vue 挂载之后渲染，下一帧再恢复焦点。
  requestAnimationFrame(() => {
    select.value?.$el.shadowRoot?.querySelector('button')?.focus({ preventScroll: true })
  })
}

defineExpose({ focus })
</script>

<template>
  <ion-select
    ref="select"
    class="cooking-style-switch"
    aria-label="做菜视图"
    :title="`当前：${currentLabel}`"
    :value="model"
    selected-text="视图"
    :toggle-icon="ioniconsChevronDown"
    interface="popover"
    :interface-options="{ header: '做菜视图', side: 'bottom', alignment: 'end' }"
    @ion-change="pendingStyle = $event.detail.value"
    @ion-dismiss="applySelection"
  >
    <ion-select-option value="classic">
      分组选择
    </ion-select-option>
    <ion-select-option value="workbench">
      并排浏览
    </ion-select-option>
  </ion-select>
</template>

<style scoped>
.cooking-style-switch {
  flex: 0 0 auto;
  width: auto;
  min-height: 44px;
  padding: 0 10px;
  border-radius: 8px;
  font-size: 17px;
  color: var(--ion-color-primary);
}
.cooking-style-switch::part(icon) {
  width: 14px;
  margin-inline-start: 4px;
  color: currentColor;
  opacity: 0.7;
}
.cooking-style-switch.ion-focused {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: -2px;
}
</style>
