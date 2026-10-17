<script lang="ts" setup>
import dayjs from 'dayjs'
import { exportLocalUserData, importLocalUserData } from '~/composables/store/userData'
import { promptText, showAlert } from '~/utils/dialog'
import { yunleHaptic } from '~/utils/yunleHost'

const { isDark, toggleDark } = useDarkMode()

const app = useAppStore()

definePageMeta({
  layout: 'child',
})

const importInput = ref<HTMLInputElement>()

function exportText(): string {
  return JSON.stringify(exportLocalUserData())
}

function exportToFile(): void {
  const blob = new Blob([exportText()], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `cook-data-${dayjs().format('YYYYMMDD-HHmm')}.json`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 0)
}

async function copyExport(): Promise<void> {
  try {
    await navigator.clipboard.writeText(exportText())
    void yunleHaptic('success')
    await showAlert('已复制', '数据已复制到剪贴板，可以在新设备上用「粘贴导入」恢复。')
  }
  catch {
    await showAlert('复制失败', '当前环境不支持写入剪贴板，请改用「导出为文件」。')
  }
}

async function applyImport(text: string): Promise<void> {
  const result = importLocalUserData(text)
  void yunleHaptic(result ? 'success' : 'heavy')
  if (result)
    await showAlert('导入完成', `已合并到本机：现有 ${result.favorites} 个收藏、${result.histories} 条历史记录。`)
  else
    await showAlert('导入失败', '无法识别这份数据，请确认它来自食用手册的导出。')
}

async function importFromFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file)
    await applyImport(await file.text())
}

function onToggleChange() {
  void yunleHaptic('light')
}

async function importFromPaste(): Promise<void> {
  const value = await promptText({ header: '粘贴导入', message: '粘贴之前复制的数据', okText: '导入' })
  if (value?.trim())
    await applyImport(value)
}
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>设置</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content :fullscreen="true">
      <h2 class="cook-group-header">
        外观
      </h2>
      <ion-list :inset="true">
        <ion-item>
          <ion-toggle :checked="isDark" justify="space-between" @ion-change="toggleDark(); onToggleChange()">
            暗色模式
          </ion-toggle>
        </ion-item>
      </ion-list>

      <h2 class="cook-group-header">
        做菜视图
      </h2>
      <ion-list :inset="true">
        <ion-item>
          <div class="view-settings">
            <RecipeViewSwitch v-model="app.recipeView" />
          </div>
        </ion-item>
      </ion-list>
      <p class="cook-group-footer">
        经典视图保留紧凑标签和原有筛选；匹配视图展示食材齐全、还差一种、还差两种。会自动记住上次选择，也可以在做菜页直接切换。
      </p>

      <h2 class="cook-group-header">
        数据
      </h2>
      <ion-list :inset="true">
        <ion-item>
          <ion-toggle v-model:checked="app.settings.keepLocalData" justify="space-between" @ion-change="onToggleChange">
            离开网页后保留选中数据
          </ion-toggle>
        </ion-item>
      </ion-list>
      <p class="cook-group-footer">
        视图偏好独立保存，不受这个开关影响。
      </p>

      <h2 class="cook-group-header">
        收藏和历史
      </h2>
      <ion-list :inset="true">
        <ion-item :button="true" :detail="false" @click="exportToFile">
          <CookListIcon slot="start" :icon="ioniconsDownloadOutline" color="#007aff" />
          <ion-label>导出为文件</ion-label>
        </ion-item>
        <ion-item :button="true" :detail="false" @click="copyExport">
          <CookListIcon slot="start" :icon="ioniconsCopyOutline" color="#5856d6" />
          <ion-label>复制到剪贴板</ion-label>
        </ion-item>
        <ion-item :button="true" :detail="false" @click="importInput?.click()">
          <CookListIcon slot="start" :icon="ioniconsFolderOpenOutline" color="#ff9500" />
          <ion-label>从文件导入</ion-label>
        </ion-item>
        <ion-item :button="true" :detail="false" @click="importFromPaste">
          <CookListIcon slot="start" :icon="ioniconsClipboardOutline" color="#34c759" />
          <ion-label>粘贴导入</ion-label>
        </ion-item>
      </ion-list>
      <p class="cook-group-footer">
        导入会和本机已有数据合并，不会覆盖。登录云乐坊账号后，收藏和历史会自动同步。
      </p>
      <input
        ref="importInput"
        hidden
        type="file"
        accept="application/json,.json"
        @change="importFromFile"
      >
    </ion-content>
  </ion-page>
</template>

<style scoped>
.view-settings {
  width: 100%;
  padding-block: 8px;
}
</style>

<style>
/* (Optional) This is added to prevent the flashing that happens when toggling between palettes */
ion-item {
  --transition: none;
}
</style>
