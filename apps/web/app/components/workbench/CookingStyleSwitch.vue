<script setup lang="ts">
import type { CookingStyle } from '~/composables/useCookingStyle'

const props = defineProps<{ desktop?: boolean }>()
const model = defineModel<CookingStyle>({ required: true })
const pendingStyle = shallowRef<CookingStyle>()
const currentLabel = computed(() => model.value === 'classic' ? '分组选择' : '并排浏览')
const select = useTemplateRef<{ $el: HTMLIonSelectElement }>('select')
const desktopSwitch = useTemplateRef<HTMLElement>('desktopSwitch')

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
    if (props.desktop) {
      desktopSwitch.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus({ preventScroll: true })
      return
    }
    select.value?.$el.shadowRoot?.querySelector('button')?.focus({ preventScroll: true })
  })
}

defineExpose({ focus })
</script>

<template>
  <div v-if="desktop" ref="desktopSwitch" class="cooking-view-segment" role="group" aria-label="做菜视图">
    <button type="button" :aria-pressed="model === 'classic'" @click="model = 'classic'">
      分组选择
    </button>
    <button type="button" :aria-pressed="model === 'workbench'" @click="model = 'workbench'">
      并排浏览
    </button>
  </div>
  <ion-select
    v-else
    ref="select"
    class="cooking-style-switch"
    aria-label="做菜视图"
    :title="`当前：${currentLabel}`"
    :value="model"
    selected-text="视图"
    :toggle-icon="ioniconsChevronDown"
    interface="popover"
    :interface-options="{ header: '做菜视图', side: 'bottom', alignment: 'center' }"
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
.cooking-view-segment {
  display: flex;
  gap: 2px;
  padding: 3px;
  border-radius: 7px;
  background: var(--cook-fill);
}
.cooking-view-segment button {
  min-height: 28px;
  padding: 4px 14px;
  border-radius: 5px;
  background: transparent;
  color: var(--ion-text-color-step-400, #6d6d72);
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
}
.cooking-view-segment button[aria-pressed='true'] {
  background: var(--ion-item-background, #fff);
  color: var(--ion-text-color, #1c1c1e);
  box-shadow: 0 1px 3px rgb(0 0 0 / 10%);
}
.cooking-view-segment button:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 1px;
}
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
