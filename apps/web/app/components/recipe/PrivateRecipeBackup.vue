<script setup lang="ts">
import type { PrivateRecipe } from '@yunyoujun/cook/custom-recipes'
import { exportPrivateRecipes, mergePrivateRecipeImport, parsePrivateRecipeFile, previewPrivateRecipeImport, PRIVATE_RECIPE_FILE_LIMIT } from '@yunyoujun/cook/custom-recipes'
import { customRecipes, updateCustomRecipes } from '~/composables/store/customRecipe'
import { createCustomRecipeId } from '~/utils/customRecipe'
import { promptText, showAlert } from '~/utils/dialog'

const input = ref<HTMLInputElement>()
const incoming = ref<PrivateRecipe[]>()
const copies = ref(false)
const error = ref('')
const preview = computed(() => previewPrivateRecipeImport(customRecipes.value, incoming.value ?? []))

async function exportFile() {
  try {
    const text = exportPrivateRecipes(customRecipes.value)
    const url = URL.createObjectURL(new Blob([text], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `cook-custom-recipes-${new Date().toISOString().slice(0, 10)}.json`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  catch {
    await showAlert('导出失败', '菜谱中有无法识别的数据，请保留本机数据后再试。')
  }
}

async function read(text: string) {
  try {
    incoming.value = parsePrivateRecipeFile(text).recipes
    copies.value = false
    error.value = ''
  }
  catch (e) {
    await showAlert('导入失败', e instanceof Error ? e.message : '没能读取备份，原有菜谱未改变。')
  }
}

async function readFile(event: Event) {
  const element = event.target as HTMLInputElement
  const file = element.files?.[0]
  element.value = ''
  if (!file)
    return
  if (file.size > PRIVATE_RECIPE_FILE_LIMIT) {
    await showAlert('导入失败', '文件超过 5 MB，原有菜谱未改变。')
    return
  }
  try {
    await read(await file.text())
  }
  catch { await showAlert('导入失败', '没能读取文件，原有菜谱未改变。') }
}

async function paste() {
  const text = await promptText({ header: '粘贴私人菜谱备份', okText: '预览' })
  if (text)
    await read(text)
}

async function confirm() {
  if (!incoming.value)
    return
  try {
    // Recompute using current state; the user may edit recipes in another tab during preview.
    const recipes = incoming.value
    updateCustomRecipes(current => mergePrivateRecipeImport(current, recipes, copies.value, createCustomRecipeId))
    incoming.value = undefined
    await showAlert('导入完成', '私人菜谱已合并到本机，现有内容没有被覆盖。')
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '没能保存，原有菜谱未改变。'
  }
}
</script>

<template>
  <ion-list :inset="true">
    <ion-item :button="true" @click="exportFile">
      导出私人菜谱
    </ion-item>
    <ion-item :button="true" @click="input?.click()">
      从文件导入私人菜谱
    </ion-item>
    <ion-item :button="true" @click="paste">
      粘贴私人菜谱备份
    </ion-item>
  </ion-list>
  <p class="cook-group-footer">
    可与云食册 iOS 版通过文件迁移。私人菜谱不会通过云乐坊账号自动同步。
  </p>
  <input ref="input" hidden type="file" accept=".json,application/json" @change="readFile">
  <ion-modal :is-open="incoming !== undefined" @did-dismiss="incoming = undefined">
    <ion-header>
      <ion-toolbar>
        <ion-title>导入预览</ion-title>
        <ion-buttons slot="start">
          <ion-button @click="incoming = undefined">
            取消
          </ion-button>
        </ion-buttons>
        <ion-buttons slot="end">
          <ion-button @click="confirm">
            确认导入
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <p class="cook-group-footer private-backup-summary">
        新增 {{ preview.additions.length }} 道，相同 {{ preview.duplicates.length }} 道，冲突 {{ preview.conflicts.length }} 道。不会覆盖现有菜谱。
      </p>
      <ion-list v-if="preview.conflicts.length" :inset="true">
        <ion-item>
          <ion-toggle v-model:checked="copies">
            冲突菜谱另存副本
          </ion-toggle>
        </ion-item>
        <ion-item v-for="recipe in preview.conflicts" :key="recipe.customId">
          <ion-label class="ion-text-wrap">
            <h3>{{ recipe.name }}</h3>
            <p>导入内容：{{ recipe.stuff.join('、') }} · {{ recipe.tools.join('、') }}</p>
            <p>{{ recipe.bv || recipe.link || '无来源链接' }}</p>
            <p>{{ recipe.note || '无备注' }}</p>
          </ion-label>
        </ion-item>
      </ion-list>
      <p v-if="preview.conflicts.length" class="cook-group-footer">
        关闭时跳过冲突；打开时保留两份。重复导入相同副本不会再次新增。
      </p>
      <ion-list :inset="true">
        <ion-item v-for="recipe in preview.additions" :key="recipe.customId">
          <ion-label>{{ recipe.name }}</ion-label>
        </ion-item>
      </ion-list>
      <p v-if="error" class="cook-group-footer" role="alert">
        {{ error }}
      </p>
    </ion-content>
  </ion-modal>
</template>

<style scoped>
.private-backup-summary {
  margin-top: 16px;
}
</style>
