<script lang="ts" setup>
import type { DbRecipeItem } from '~/utils/db'
import RecipeFeedbackButton from '~/components/feedback/RecipeButton.vue'
import AndroidRecipeLink from '~/components/recipe/AndroidRecipeLink.vue'
import { useIndexedDB } from '~/composables/db'
import { sharedRecipePageTitle } from '~/constants/seo'
import { db } from '~/utils/db'
import { getRecipeLink, getRecipeTextLink } from '~/utils/recipe'

const router = useRouter()
const { init } = useIndexedDB()
const recipe = shallowRef<DbRecipeItem>()
const loadState = shallowRef<'loading' | 'ready' | 'missing' | 'failed'>('loading')
const retries = shallowRef(0)

const recipeID = computed(() => {
  const id = router.currentRoute.value.query.id
  if (typeof id !== 'string' || !/^(?:0|[1-9]\d*)$/.test(id))
    return undefined
  const value = Number(id)
  return Number.isSafeInteger(value) ? value : undefined
})
const sourceURL = computed(() => recipe.value ? getRecipeLink(recipe.value) : '')
const textURL = computed(() => recipe.value ? getRecipeTextLink(recipe.value) : '')

const sharePageMeta = [{ name: 'robots' as const, content: 'noindex' }]
const head = useHead({ title: sharedRecipePageTitle, meta: sharePageMeta })
watch(() => recipe.value?.name, (name) => {
  head.patch({ title: name ? `${name} · 食用手册` : sharedRecipePageTitle, meta: sharePageMeta })
})

watch([recipeID, retries], async ([id], _, onCleanup) => {
  recipe.value = undefined
  if (id === undefined) {
    loadState.value = 'missing'
    return
  }

  let active = true
  onCleanup(() => {
    active = false
  })
  loadState.value = 'loading'
  try {
    await init()
    const item = await db.recipes.get(id)
    if (active) {
      recipe.value = item
      loadState.value = item ? 'ready' : 'missing'
    }
  }
  catch {
    if (active)
      loadState.value = 'failed'
  }
}, { immediate: true })
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/library" />
        </ion-buttons>
        <ion-title>菜谱</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <article v-if="recipe" class="shared-recipe">
        <h1>{{ recipe.name }}</h1>
        <ion-list :inset="true">
          <AndroidRecipeLink :recipe-id="recipeID" />
          <ion-item v-if="recipe.stuff.length">
            <ion-label class="ion-text-wrap">
              <h2>食材</h2>
              <p>{{ recipe.stuff.join('、') }}</p>
            </ion-label>
          </ion-item>
          <ion-item v-if="recipe.tools.length">
            <ion-label class="ion-text-wrap">
              <h2>厨具</h2>
              <p>{{ recipe.tools.map(tool => tool === '一口大锅' ? '炒锅' : tool).join('、') }}</p>
            </ion-label>
          </ion-item>
          <ion-item v-if="sourceURL" :href="sourceURL" target="_blank" rel="noopener noreferrer" :detail="true">
            <ion-label>{{ recipe.bv ? '去 B 站看做法' : '看图文做法' }}</ion-label>
          </ion-item>
          <ion-item v-if="textURL" :href="textURL" target="_blank" rel="noopener noreferrer" :detail="true">
            <ion-label>看图文做法</ion-label>
          </ion-item>
        </ion-list>
        <RecipeFeedbackButton :recipe="recipe" />
      </article>
      <div v-else class="cook-empty" aria-live="polite">
        <p v-if="loadState === 'loading'">
          正在加载菜谱…
        </p>
        <template v-else-if="loadState === 'failed'">
          <h1>菜谱暂时没有加载成功</h1>
          <ion-button fill="clear" @click="retries++">
            重试
          </ion-button>
        </template>
        <template v-else>
          <h1>找不到这道菜</h1>
          <ion-button fill="clear" router-link="/library">
            浏览菜谱
          </ion-button>
        </template>
        <AndroidRecipeLink :recipe-id="recipeID" />
      </div>
    </ion-content>
  </ion-page>
</template>

<style scoped>
.shared-recipe {
  max-width: 44rem;
  margin: 24px auto;
}

.shared-recipe h1 {
  margin: 0 16px 16px;
  font-size: 1.75rem;
  font-weight: 700;
}
</style>
