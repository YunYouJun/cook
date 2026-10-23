<script setup lang="ts">
import type { LocalRecipeVideo } from '~/utils/localRecipeVideo'
import { onIonViewWillLeave } from '@ionic/vue'
import { liveQuery } from 'dexie'
import { shallowRef, watch } from 'vue'
import { confirmDestructive } from '~/utils/dialog'
import { checkPlayableLocalVideo, localVideoDB } from '~/utils/localRecipeVideo'

const props = defineProps<{ customId: string, recipeName: string, editable?: boolean }>()
const fileInput = shallowRef<HTMLInputElement>()
const attachment = shallowRef<LocalRecipeVideo>()
const videoElement = shallowRef<HTMLVideoElement>()
onIonViewWillLeave(() => videoElement.value?.pause())
const videoURL = shallowRef('')
const error = shallowRef('')
const busy = shallowRef(false)
const retry = shallowRef(0)
watch([() => props.customId, retry], ([id], _, onCleanup) => {
  attachment.value = undefined
  videoURL.value = ''
  error.value = ''
  let url = ''
  const subscription = liveQuery(() => localVideoDB.videos.get(id)).subscribe({
    next(value) {
      if (url)
        URL.revokeObjectURL(url)
      attachment.value = value
      url = value ? URL.createObjectURL(value.blob) : ''
      videoURL.value = url
    },
    error() { error.value = '本机视频读取失败，请重试。' },
  })
  onCleanup(() => {
    subscription.unsubscribe()
    if (url)
      URL.revokeObjectURL(url)
  })
}, { immediate: true })

async function choose(event: Event) {
  const element = event.target as HTMLInputElement
  const file = element.files?.[0]
  element.value = ''
  if (!file)
    return
  busy.value = true
  const id = props.customId
  try {
    await checkPlayableLocalVideo(file)
    await localVideoDB.saveVideo(id, props.recipeName, file)
    error.value = ''
  }
  catch (e) { error.value = `视频未能保存，原视频保留。${e instanceof Error ? e.message : '请检查剩余空间后重试。'}` }
  finally { busy.value = false }
}
async function remove() {
  if (!await confirmDestructive({ header: '移除本机视频？其他设备及已导出的文件不受影响。', confirmText: '移除' }))
    return
  busy.value = true
  try {
    await localVideoDB.videos.delete(props.customId)
    error.value = ''
  }
  catch { error.value = '视频移除失败，请重试。' }
  finally { busy.value = false }
}
</script>

<template>
  <section class="local-recipe-video" aria-label="本机视频">
    <h3>本机视频</h3>
    <video v-if="videoURL" ref="videoElement" :key="videoURL" :src="videoURL" controls playsinline preload="metadata" @error="error = '当前浏览器无法播放此视频，可以导出后用其他播放器打开。'" />
    <p v-if="attachment">
      {{ attachment.filename }}
    </p>
    <p v-else>
      这台设备尚未添加视频。
    </p>
    <p>视频仅保存在本机，不随 iCloud 或菜谱 JSON 迁移。清理浏览器数据会删除视频，请单独导出备份。</p>
    <ion-button v-if="editable" :disabled="busy" fill="outline" @click="fileInput?.click()">
      {{ attachment ? '替换本机视频' : '添加本机视频' }}
    </ion-button>
    <ion-button v-if="attachment" :href="videoURL" :download="attachment.filename" fill="outline">
      导出视频
    </ion-button>
    <ion-button v-if="editable && attachment" :disabled="busy" fill="clear" color="danger" @click="remove">
      移除视频
    </ion-button>
    <p v-if="busy" role="status">
      正在保存视频…
    </p>
    <p v-if="error" role="alert">
      {{ error }} <ion-button fill="clear" @click="retry++">
        重试读取
      </ion-button>
    </p>
    <input ref="fileInput" hidden type="file" accept="video/mp4,video/quicktime,video/webm,.mp4,.mov,.webm" @change="choose">
  </section>
</template>

<style scoped>
.local-recipe-video {
  margin: 16px;
}
.local-recipe-video video {
  width: 100%;
  max-height: 360px;
  background: #000;
  border-radius: 12px;
}
.local-recipe-video p {
  color: var(--ion-color-medium);
  font-size: 14px;
  overflow-wrap: anywhere;
}
</style>
