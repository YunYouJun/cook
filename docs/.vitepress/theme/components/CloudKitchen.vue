<script setup lang="ts">
import type { KitchenScene } from '../cloud-kitchen/types'
import { onBeforeUnmount, onMounted, shallowRef, useTemplateRef, watch } from 'vue'

const props = defineProps<{
  selected: readonly string[]
  cooking: boolean
  dark: boolean
}>()

const emit = defineEmits<{
  select: [name: string]
  cook: []
}>()

const host = useTemplateRef<HTMLDivElement>('host')
const status = shallowRef<'loading' | 'ready' | 'unavailable'>('loading')
const hovered = shallowRef<string | null>(null)
let scene: KitchenScene | undefined
let mounted = false
let motionQuery: MediaQueryList | undefined
let loadObserver: IntersectionObserver | undefined

function syncMotion() {
  scene?.setReducedMotion(motionQuery?.matches ?? false)
}

async function loadScene() {
  loadObserver?.disconnect()
  try {
    const { createKitchenScene } = await import('../cloud-kitchen/scene')
    if (!mounted || !host.value)
      return
    scene = createKitchenScene(host.value, {
      selected: props.selected,
      cooking: props.cooking,
      dark: props.dark,
      reducedMotion: motionQuery?.matches ?? false,
      onSelect: name => emit('select', name),
      onCook: () => emit('cook'),
      onHover: name => hovered.value = name,
      onUnavailable: () => {
        status.value = 'unavailable'
        hovered.value = null
        scene?.dispose()
        scene = undefined
      },
    })
    status.value = 'ready'
  }
  catch {
    status.value = 'unavailable'
  }
}

onMounted(() => {
  mounted = true
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  motionQuery.addEventListener('change', syncMotion)
  loadObserver = new IntersectionObserver(([entry]) => {
    if (entry?.isIntersecting)
      void loadScene()
  }, { rootMargin: '200px' })
  if (host.value)
    loadObserver.observe(host.value)
})

watch(() => [props.selected.slice(), props.cooking, props.dark], () => {
  scene?.update({ selected: props.selected, cooking: props.cooking, dark: props.dark })
})

onBeforeUnmount(() => {
  mounted = false
  loadObserver?.disconnect()
  motionQuery?.removeEventListener('change', syncMotion)
  scene?.dispose()
})
</script>

<template>
  <div class="cloud-kitchen" :class="{ 'is-ready': status === 'ready' }">
    <div ref="host" class="cloud-kitchen-canvas" aria-hidden="true" />
    <div v-if="status !== 'ready'" class="cloud-kitchen-placeholder" role="status">
      <svg class="cloud-kitchen-sketch" viewBox="0 0 320 230" aria-hidden="true">
        <path d="M37 177c-21-18-5-43 19-38-5-29 35-45 49-20 21-31 63-31 80-6 26-25 60-8 61 16 36-5 58 30 35 47-13 23-225 31-244 1" fill="#e8f2ff" />
        <path d="m64 142 101-53 108 57-104 58z" fill="#dca66c" />
        <path d="M64 142V66l101-40v63z" fill="#fff4df" />
        <path d="m165 26 108 58v62L165 89z" fill="#f6e7ce" />
        <path d="m78 77 59-24v35l-59 27z" fill="#a6d2e4" stroke="#fff" stroke-width="5" />
        <path d="m169 105 89 44v-37l-89-44z" fill="#7faed0" />
        <path d="m164 68 96 45 11-8-97-46z" fill="#efd0a0" />
        <path d="m94 136 66-32 62 31-65 37z" fill="#f6d49d" />
        <path d="M98 139v33l59 33v-33z" fill="#75a7cb" />
        <path d="m157 172 60-34v34l-60 33z" fill="#5890b9" />
        <path d="M192 85v17c0 17 36 17 36 0V85" fill="#347da9" />
        <ellipse cx="210" cy="85" rx="18" ry="8" fill="#efc56e" />
        <path d="M205 73c-14-13 13-14 2-28m14 30c-8-9 8-14 3-24" fill="none" stroke="#fff" stroke-width="6" stroke-linecap="round" />
        <circle cx="146" cy="138" r="9" fill="#ee7255" />
        <ellipse cx="171" cy="140" rx="7" ry="10" fill="#fff3d6" />
      </svg>
      <p>{{ status === 'loading' ? '正在布置你的云厨房…' : '云厨房准备好了，选几样食材试试吧' }}</p>
    </div>
    <Transition name="kitchen-hint">
      <span v-if="hovered && status === 'ready'" class="cloud-kitchen-hint" aria-hidden="true">{{ hovered }}</span>
    </Transition>
  </div>
</template>

<style scoped>
.cloud-kitchen {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 360px;
  isolation: isolate;
}

.cloud-kitchen-canvas {
  position: absolute;
  inset: 0;
  opacity: 0;
  transition: opacity 0.5s ease;
}

.is-ready .cloud-kitchen-canvas {
  opacity: 1;
}

.cloud-kitchen-placeholder {
  position: absolute;
  inset: 0;
  display: grid;
  align-content: center;
  justify-items: center;
  color: var(--cook-hero-muted, #416183);
}

.cloud-kitchen-sketch {
  width: min(76%, 460px);
  filter: drop-shadow(0 22px 28px #7297bf24);
}

.cloud-kitchen-placeholder p {
  margin: 0;
  font-size: 14px;
}

.cloud-kitchen-hint {
  position: absolute;
  bottom: 5%;
  left: 50%;
  z-index: 1;
  transform: translateX(-50%);
  padding: 8px 16px;
  border: 1px solid #ffffffaa;
  border-radius: 99px;
  background: #fff9e9ee;
  box-shadow: 0 4px 15px #325f7a14;
  color: #35516d;
  font-size: 13px;
  white-space: nowrap;
  pointer-events: none;
}

.kitchen-hint-enter-active,
.kitchen-hint-leave-active {
  transition: opacity 0.15s;
}

.kitchen-hint-enter-from,
.kitchen-hint-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .cloud-kitchen-canvas,
  .kitchen-hint-enter-active,
  .kitchen-hint-leave-active {
    transition: none;
  }
}
</style>
