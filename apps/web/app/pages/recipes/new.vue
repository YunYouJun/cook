<script lang="ts" setup>
import type { CustomRecipe } from '~/composables/store/customRecipe'
import type { StuffItem } from '~/types'
import { onIonViewWillEnter } from '@ionic/vue'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import { getCustomRecipe, removeCustomRecipe, saveCustomRecipe } from '~/composables/store/customRecipe'
import { meat, staple, tools, vegetable } from '~/data/food'
import { buildCustomRecipe, CUSTOM_RECIPE_NAME_MAX } from '~/utils/customRecipe'
import { confirmDestructive } from '~/utils/dialog'
import { getRecipeLink } from '~/utils/recipe'
import { yunleHaptic } from '~/utils/yunleHost'

definePageMeta({
  layout: 'child',
  title: '添加菜谱',
})

/**
 * 添加 / 编辑自己的菜谱：带 ?id= 时编辑已有的那道
 */
const router = useRouter()
const ionRouter = useIonRouter()

const existing = shallowRef<CustomRecipe>()
const name = ref('')
const stuff = ref<string[]>([])
const selectedTools = ref<string[]>([])
const source = ref('')
const note = ref('')
const error = ref('')
const extraTool = ref('')
const customTools = computed(() => selectedTools.value.filter(name => !tools.some(tool => tool.name === name)))

function addTool() {
  const text = extraTool.value.trim()
  if (text && text.length <= 100 && !selectedTools.value.includes(text))
    selectedTools.value = [...selectedTools.value, text]
  extraTool.value = ''
}

// Ionic 会复用路由栈里的页面，所以每次进入都按当前地址重新填表；
// 在 ion-router-outlet 里 useRoute 读不到这一页的 ?id=，直接读 router
function fillForm() {
  const id = router.currentRoute.value.query.id
  const recipe = typeof id === 'string' ? getCustomRecipe(id) : undefined
  existing.value = recipe
  name.value = recipe?.name ?? ''
  stuff.value = recipe ? [...recipe.stuff] : []
  selectedTools.value = recipe ? [...recipe.tools] : []
  source.value = recipe ? getRecipeLink(recipe) : ''
  note.value = recipe?.note ?? ''
  error.value = ''
}
fillForm()
onIonViewWillEnter(fillForm)

const sections = [
  { title: '蔬菜', items: vegetable },
  { title: '肉和蛋', items: meat },
  { title: '主食', items: staple },
]

function selectedNote(items: StuffItem[]) {
  const count = items.filter(item => stuff.value.includes(item.name)).length
  return count ? `已选 ${count} 种` : ''
}

function addStuff(text: string) {
  stuff.value = uniqueStuff([...stuff.value, text])
  error.value = ''
}

function toggleStuff(item: string) {
  if (stuff.value.includes(item))
    stuff.value = stuff.value.filter(name => name !== item)
  else
    addStuff(item)
  void yunleHaptic('light')
}

function toggleTool(item: string) {
  selectedTools.value = selectedTools.value.includes(item)
    ? selectedTools.value.filter(name => name !== item)
    : [...selectedTools.value, item]
  void yunleHaptic('light')
}

function save() {
  const result = buildCustomRecipe({
    name: name.value,
    stuff: stuff.value,
    tools: selectedTools.value,
    source: source.value,
    note: note.value,
  }, existing.value)
  if ('error' in result) {
    error.value = result.error
    void yunleHaptic('heavy')
    return
  }
  try {
    saveCustomRecipe(result.recipe)
  }
  catch {
    error.value = '没能保存，请检查本机存储空间或先备份菜谱。原有菜谱未改变。'
    return
  }
  void yunleHaptic('success')
  ionRouter.navigate('/recipes/mine', 'back', 'replace')
}

async function remove() {
  const recipe = existing.value
  if (!recipe)
    return
  if (await confirmDestructive({ header: `删除「${recipe.name}」后不可恢复`, confirmText: '删除菜谱' })) {
    try {
      removeCustomRecipe(recipe.customId)
    }
    catch {
      error.value = '没能删除，原有菜谱未改变。'
      return
    }
    void yunleHaptic('success')
    ionRouter.navigate('/recipes/mine', 'back', 'replace')
  }
}
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/recipes/mine" />
        </ion-buttons>
        <ion-title>{{ existing ? '编辑菜谱' : '添加菜谱' }}</ion-title>
        <ion-buttons slot="end">
          <ion-button :strong="true" @click="save">
            保存
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content :fullscreen="true" class="custom-recipe-form">
      <ion-list :inset="true">
        <ion-item>
          <ion-input
            v-model="name"
            label="菜名"
            placeholder="例如：外婆的红烧肉"
            :maxlength="CUSTOM_RECIPE_NAME_MAX"
            :clear-input="true"
            @ion-input="error = ''"
          />
        </ion-item>
      </ion-list>

      <h2 class="cook-group-header">
        食材 · {{ stuff.length }} 种
      </h2>
      <IngredientInput :selected="stuff" @add="addStuff" @remove="toggleStuff" />
      <IngredientSection v-for="section in sections" :key="section.title" :title="section.title" :note="selectedNote(section.items)">
        <IngredientChip
          v-for="item in section.items"
          :key="item.name"
          :label="item.name"
          :emoji="item.emoji"
          :selected="stuff.includes(item.name)"
          @click="toggleStuff(item.name)"
        />
      </IngredientSection>

      <IngredientSection title="厨具（可选）">
        <IngredientChip
          v-for="item in tools"
          :key="item.name"
          :label="item.name === '一口大锅' ? '炒锅' : (item.label || item.name)"
          :icon="item.icon"
          :selected="selectedTools.includes(item.name)"
          @click="toggleTool(item.name)"
        />
        <IngredientChip v-for="tool in customTools" :key="tool" :label="tool" :selected="true" @click="toggleTool(tool)" />
      </IngredientSection>
      <ion-list :inset="true">
        <ion-item>
          <ion-input v-model="extraTool" label="其他厨具" placeholder="如：电蒸锅" :maxlength="100" @keydown.enter.prevent="addTool" />
          <ion-button slot="end" fill="clear" @click="addTool">
            添加
          </ion-button>
        </ion-item>
      </ion-list>

      <h2 class="cook-group-header">
        做法（可选）
      </h2>
      <ion-list :inset="true" class="custom-recipe-fields">
        <ion-item>
          <ion-input
            v-model="source"
            label="链接"
            placeholder="B 站链接、BV 号或网址"
            inputmode="url"
            :clear-input="true"
            @ion-input="error = ''"
          />
        </ion-item>
        <ion-item>
          <ion-textarea
            v-model="note"
            label="备注"
            label-placement="stacked"
            placeholder="没有视频的话，简单记一下步骤和用量"
            :auto-grow="true"
            :rows="3"
          />
        </ion-item>
      </ion-list>

      <p v-if="error" class="cook-group-footer custom-recipe-error" role="alert">
        {{ error }}
      </p>
      <p class="cook-group-footer">
        自己添加的菜谱只保存在这台设备上，会和收录的菜谱一起参与搭配推荐和「吃什么」。
      </p>

      <ion-list v-if="existing" :inset="true" class="custom-recipe-delete">
        <ion-item :button="true" :detail="false" @click="remove">
          <ion-label class="ion-text-center" color="danger">
            删除菜谱
          </ion-label>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>

<style scoped>
.custom-recipe-form .cook-group-header {
  margin-top: 20px;
}
.custom-recipe-fields {
  margin-top: 0;
}
.custom-recipe-error {
  color: var(--ion-color-danger);
}
.custom-recipe-delete {
  margin-bottom: 40px;
}
</style>
