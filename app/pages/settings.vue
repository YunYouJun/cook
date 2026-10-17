<script lang="ts" setup>
import { Dialog } from '@capacitor/dialog'
import dayjs from 'dayjs'
import { exportLocalUserData, importLocalUserData } from '~/composables/store/userData'

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
    await Dialog.alert({ title: '已复制', message: '数据已复制到剪贴板，可以在新设备上用「粘贴导入」恢复。' })
  }
  catch {
    await Dialog.alert({ title: '复制失败', message: '当前环境不支持写入剪贴板，请改用「导出为文件」。' })
  }
}

async function applyImport(text: string): Promise<void> {
  const result = importLocalUserData(text)
  await Dialog.alert(result
    ? { title: '导入完成', message: `已合并到本机：现有 ${result.favorites} 个收藏、${result.histories} 条历史记录。` }
    : { title: '导入失败', message: '无法识别这份数据，请确认它来自食用手册的导出。' })
}

async function importFromFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (file)
    await applyImport(await file.text())
}

async function importFromPaste(): Promise<void> {
  const { value, cancelled } = await Dialog.prompt({
    title: '粘贴导入',
    message: '粘贴之前复制的数据',
    okButtonTitle: '导入',
    cancelButtonTitle: '取消',
  })
  if (!cancelled && value.trim())
    await applyImport(value)
}
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>设置</ion-title>
        <!-- <ion-buttons slot="end">
          <ion-button color="dark">
            <ion-icon slot="icon-only" :icon="ioniconsPersonCircleOutline" />
          </ion-button>
        </ion-buttons> -->
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <ion-list-header>外观</ion-list-header>
      <ion-list :inset="true">
        <ion-item>
          <ion-toggle :checked="isDark" justify="space-between" @ion-change="toggleDark">
            暗色模式
          </ion-toggle>
        </ion-item>
      </ion-list>

      <ion-list-header>做菜视图</ion-list-header>
      <ion-list :inset="true">
        <ion-item>
          <div class="view-settings">
            <RecipeViewSwitch v-model="app.recipeView" />
            <p>经典视图保留紧凑标签和原有筛选；匹配视图展示食材齐全、还差一种、还差两种。</p>
            <p>自动记住上次选择，下次打开继续使用。也可以在做菜页直接切换。</p>
            <p class="view-settings-note">
              视图偏好独立保存，不受下方食材数据开关影响。
            </p>
          </div>
        </ion-item>
      </ion-list>

      <ion-list-header>数据</ion-list-header>
      <ion-list :inset="true">
        <ion-item>
          <ion-toggle v-model:checked="app.settings.keepLocalData" justify="space-between">
            离开网页后保留选中数据
          </ion-toggle>
        </ion-item>
      </ion-list>

      <ion-list-header>收藏和历史</ion-list-header>
      <ion-list :inset="true">
        <ion-item :button="true" @click="exportToFile">
          <ion-icon slot="start" :icon="ioniconsDownloadOutline" />
          <ion-label>导出为文件</ion-label>
        </ion-item>
        <ion-item :button="true" @click="copyExport">
          <ion-icon slot="start" :icon="ioniconsCopyOutline" />
          <ion-label>复制到剪贴板</ion-label>
        </ion-item>
        <ion-item :button="true" @click="importInput?.click()">
          <ion-icon slot="start" :icon="ioniconsFolderOpenOutline" />
          <ion-label>从文件导入</ion-label>
        </ion-item>
        <ion-item :button="true" @click="importFromPaste">
          <ion-icon slot="start" :icon="ioniconsClipboardOutline" />
          <ion-label>粘贴导入</ion-label>
        </ion-item>
        <ion-item lines="none">
          <ion-label class="ion-text-wrap view-settings-note">
            导入会和本机已有数据合并，不会覆盖。登录云乐坊账号后，收藏和历史会自动同步。
          </ion-label>
        </ion-item>
      </ion-list>
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
  padding-bottom: 16px;
  font-size: 14px;
  line-height: 1.7;
}
.view-settings p + p {
  margin-top: 8px;
}
.view-settings-note {
  font-size: 12px;
  color: var(--ion-color-medium-shade);
}
</style>

<style>
/* (Optional) This is added to prevent the flashing that happens when toggling between palettes */
ion-item {
  --transition: none;
}
</style>
