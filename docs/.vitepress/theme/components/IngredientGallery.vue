<script setup lang="ts">
import type { IngredientId } from '../cloud-kitchen/ingredient-catalog'
import type { createIngredientPreview } from '../cloud-kitchen/ingredient-preview'
import { computed, onBeforeUnmount, onMounted, shallowRef, useTemplateRef, watch } from 'vue'
import { ingredientCatalog } from '../cloud-kitchen/ingredient-catalog'

const current = shallowRef<IngredientId>('tomato')
const ingredient = computed(() => ingredientCatalog.find(item => item.id === current.value)!)
const selected = shallowRef(false)
const reduced = shallowRef(false)
const status = shallowRef('loading')
const notice = shallowRef('')
const host = useTemplateRef<HTMLDivElement>('previewHost')
let preview: ReturnType<typeof createIngredientPreview> | undefined
let mounted = false
let motion: MediaQueryList | undefined
function syncMotion() {
  reduced.value = motion?.matches ?? false
}
function pick(id: IngredientId) {
  current.value = id
  selected.value = false
  notice.value = ''
}
function toggle() {
  selected.value = !selected.value
  notice.value = selected.value ? `已选${ingredient.value.name}` : '已放回台面'
}
function cook() {
  preview?.cook()
  notice.value = reduced.value ? '已入锅（减少动态效果已开启）' : `${ingredient.value.name}入锅，咕嘟咕嘟。`
}
onMounted(async () => {
  mounted = true
  motion = matchMedia('(prefers-reduced-motion: reduce)')
  motion.addEventListener('change', syncMotion)
  syncMotion()
  try {
    const { createIngredientPreview } = await import('../cloud-kitchen/ingredient-preview')
    if (!mounted || !host.value)
      return
    preview = createIngredientPreview(host.value, toggle, () => {
      status.value = 'failed'
      preview?.dispose()
      preview = undefined
    })
    preview.update(current.value, selected.value, reduced.value)
    status.value = 'ready'
  }
  catch { status.value = 'failed' }
})
watch([current, selected, reduced], () => preview?.update(current.value, selected.value, reduced.value))
onBeforeUnmount(() => {
  mounted = false
  motion?.removeEventListener('change', syncMotion)
  preview?.dispose()
})
</script>

<template>
  <section class="ingredient-gallery" aria-label="食材模型展柜">
    <div class="gallery-stage">
      <div ref="previewHost" class="model-preview" aria-label="食材三维预览" />
      <img v-if="status !== 'ready'" class="model-fallback" :src="`/models/ingredients/${current}.png`" :alt="ingredient.name">
      <span class="stage-state">{{ selected ? '已选入食材篮' : '点模型，选入食材篮' }}</span>
    </div>
    <div class="gallery-detail">
      <div>
        <h2 class="ingredient-title">
          {{ ingredient.name }}
        </h2>
        <p class="ingredient-detail">
          {{ ingredient.detail }}
        </p>
      </div>
      <div class="gallery-actions">
        <button class="primary-action" type="button" :aria-pressed="selected" @click="toggle">
          {{ selected ? '取消选择' : `试试${ingredient.motion}` }}
        </button>
        <button type="button" :disabled="!selected || status !== 'ready'" @click="cook">
          入锅试试
        </button>
        <button type="button" :disabled="status !== 'ready'" @click="preview?.rotate()">
          转个角度
        </button>
      </div>
      <p class="gallery-hint" role="status">
        {{ notice || (status === 'failed' ? '三维预览暂不可用，仍可查看静态图和下载模型。' : '左右拖动旋转；点击食材或使用按钮查看反馈。') }}
      </p>
    </div>
    <div class="ingredient-shelf" role="group" aria-label="选择展示的食材">
      <button v-for="item in ingredientCatalog" :key="item.id" type="button" :aria-pressed="current === item.id" :aria-label="`查看${item.name}模型`" @click="pick(item.id)">
        <img :src="`/models/ingredients/${item.id}.png`" alt="" width="96" height="96">
        <span>{{ item.name }}</span>
      </button>
    </div>
    <div class="gallery-downloads">
      <span>同一份模型，用在厨房、食材篮与原生 App。</span>
      <a :href="`/models/ingredients/${current}.usdz`" :download="`${current}.usdz`">下载 {{ ingredient.name }} USDZ</a>
      <a :href="`/models/ingredients/${current}.png`" :download="`${current}.png`">透明 PNG</a>
    </div>
  </section>
</template>

<style scoped>
.ingredient-gallery {
  margin: 28px 0;
  overflow: hidden;
  border: 1px solid var(--vp-c-divider);
  border-radius: 24px;
  background: var(--vp-c-bg-soft);
}
.gallery-stage {
  position: relative;
  height: 350px;
  background: #eaf4f8;
}
.model-preview {
  width: 100%;
  height: 100%;
}
.model-fallback {
  position: absolute;
  width: 230px;
  height: 230px;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  object-fit: contain;
  pointer-events: none;
}
.stage-state {
  position: absolute;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 12px;
  color: #526a78;
  white-space: nowrap;
  pointer-events: none;
}
.gallery-detail {
  padding: 22px 26px 12px;
}
.ingredient-title {
  margin: 0;
  padding: 0;
  border: 0;
  font-size: 25px;
  letter-spacing: -0.02em;
}
.ingredient-detail {
  margin: 6px 0 18px;
  font-size: 14px;
  color: var(--vp-c-text-2);
}
.gallery-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.gallery-actions button {
  min-height: 42px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 10px;
  padding: 8px 16px;
  font-size: 14px;
  background: var(--vp-c-bg);
  cursor: pointer;
}
.gallery-actions .primary-action {
  color: white;
  background: #3f6e92;
  border-color: #3f6e92;
}
.gallery-actions button:disabled {
  opacity: 0.45;
  cursor: default;
}
.gallery-hint {
  min-height: 22px;
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--vp-c-text-2);
}
.ingredient-shelf {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 5px;
  padding: 10px 20px 20px;
}
.ingredient-shelf button {
  border: 1px solid transparent;
  border-radius: 14px;
  padding: 2px 0 10px;
  cursor: pointer;
  font-size: 12px;
}
.ingredient-shelf button[aria-pressed='true'] {
  border-color: #80a9c5;
  background: var(--vp-c-bg);
}
.ingredient-shelf img {
  width: 100%;
  height: auto;
}
.gallery-downloads {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 16px 26px;
  border-top: 1px solid var(--vp-c-divider);
  font-size: 12px;
}
.gallery-downloads span {
  flex-basis: 100%;
  color: var(--vp-c-text-2);
}
.ingredient-gallery button:focus-visible {
  outline: 3px solid #80a9c5;
  outline-offset: 3px;
}
:global(.dark .ingredient-gallery .gallery-stage) {
  background: #253846;
}
:global(.dark .ingredient-gallery .stage-state) {
  color: #c3d8e5;
}
@media (max-width: 600px) {
  .gallery-stage {
    height: 300px;
  }
  .gallery-detail {
    padding-inline: 18px;
  }
  .ingredient-shelf {
    grid-template-columns: repeat(4, minmax(0, 1fr));
    padding-inline: 12px;
  }
  .ingredient-shelf img {
    max-width: 100px;
    margin: auto;
  }
  .gallery-downloads {
    padding-inline: 18px;
  }
}
</style>
