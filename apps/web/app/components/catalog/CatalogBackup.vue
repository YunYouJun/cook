<script setup lang="ts">
import type { PersonalCatalog } from '@yunyoujun/cook/personal-catalog'
import { CATALOG_FILE_LIMIT, exportPersonalCatalog, mergePersonalCatalog, parsePersonalCatalog } from '@yunyoujun/cook/personal-catalog'
import { shallowRef } from 'vue'

const catalog = usePersonalCatalog()
const input = shallowRef<HTMLInputElement>()
const incoming = shallowRef<PersonalCatalog>()
const error = shallowRef('')
function exportFile() {
  try {
    const url = URL.createObjectURL(new Blob([exportPersonalCatalog(catalog.value.value)], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'cook-personal-catalog.json'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  catch (e) { error.value = e instanceof Error ? e.message : '导出失败，请重试。' }
}
async function read(event: Event) {
  const element = event.target as HTMLInputElement
  const file = element.files?.[0]
  element.value = ''
  incoming.value = undefined
  if (!file)
    return
  try {
    if (file.size > CATALOG_FILE_LIMIT)
      throw new Error('文件不能超过 256 KB。')
    incoming.value = parsePersonalCatalog(await file.text())
    error.value = ''
  }
  catch (e) { error.value = e instanceof Error ? e.message : '无法读取文件，原有配置未改变。' }
}
function apply() {
  if (!incoming.value)
    return
  try {
    const imported = incoming.value
    catalog.update(current => mergePersonalCatalog(current, imported))
    incoming.value = undefined
    error.value = ''
  }
  catch (e) { error.value = e instanceof Error ? e.message : '保存失败，请重试；原有配置未改变。' }
}
</script>

<template>
  <ion-list :inset="true">
    <ion-item :button="true" :disabled="!!catalog.error.value" @click="exportFile">
      <ion-label>导出食材和厨具</ion-label>
    </ion-item>
    <ion-item :button="true" @click="input?.click()">
      <ion-label>导入食材和厨具</ion-label>
    </ion-item>
  </ion-list>
  <p class="cook-group-footer">
    配置只保存在本机，可在网页版与 iOS App 间导入导出。私人菜谱请在「我的菜谱」单独备份。
  </p>
  <input ref="input" hidden type="file" accept=".json,application/json" @change="read">
  <ion-list v-if="incoming" :inset="true" aria-label="导入预览">
    <ion-item>
      <ion-label class="ion-text-wrap">
        将合并 {{ incoming.items.length }} 个自定义条目和 {{ incoming.hidden.length }} 个隐藏偏好。重复名称保留本机类别，已有菜谱和忌口不会改变。
      </ion-label>
    </ion-item>
    <ion-item :button="true" @click="apply">
      <ion-label>确认合并</ion-label>
    </ion-item>
    <ion-item :button="true" @click="incoming = undefined">
      <ion-label>取消导入</ion-label>
    </ion-item>
  </ion-list>
  <p v-if="error" role="alert" class="cook-group-footer">
    {{ error }}
  </p>
</template>
