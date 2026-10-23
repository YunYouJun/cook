import type { Table } from 'dexie'
import Dexie from 'dexie'

export interface LocalRecipeVideo {
  customId: string
  recipeName: string
  filename: string
  blob: Blob
}
export const LOCAL_VIDEO_LIMIT = 200 * 1024 * 1024
export class LocalVideoDatabase extends Dexie {
  videos!: Table<LocalRecipeVideo, string>
  constructor(name = 'cook-local-videos') {
    super(name)
    this.version(1).stores({ videos: 'customId' })
  }

  async saveVideo(customId: string, recipeName: string, file: File) {
    if (!customId)
      throw new Error('请先保存菜谱。')
    validateLocalVideoFile(file)
    // A single IndexedDB transaction replaces the attachment only after its Blob is stored.
    await this.videos.put({ customId, recipeName, filename: file.name, blob: file })
  }
}
export const localVideoDB = new LocalVideoDatabase()

export function validateLocalVideoFile(file: File) {
  if (!file.size || file.size > LOCAL_VIDEO_LIMIT)
    throw new Error('请选择不超过 200 MB 的视频。')
  if (!/\.(?:mp4|mov|webm)$/i.test(file.name) || (file.type && !file.type.startsWith('video/')))
    throw new Error('请选择 MP4、MOV 或 WebM 视频。')
}

/** Validate actual media before replacing a previously playable attachment. */
export async function checkPlayableLocalVideo(file: File): Promise<void> {
  validateLocalVideoFile(file)
  const url = URL.createObjectURL(file)
  const video = document.createElement('video')
  try {
    await new Promise<void>((resolve, reject) => {
      let timeout: ReturnType<typeof setTimeout>
      const finish = (error?: Error) => {
        clearTimeout(timeout)
        video.onloadedmetadata = null
        video.onerror = null
        if (error)
          reject(error)
        else
          resolve()
      }
      timeout = setTimeout(() => finish(new Error('视频读取超时，请重试或选择较小的文件。')), 15000)
      video.onloadedmetadata = () => finish(video.videoWidth > 0 ? undefined : new Error('文件中没有可播放的视频画面。'))
      video.onerror = () => finish(new Error('当前浏览器无法读取这个视频，请转换为兼容的 MP4 后重试。'))
      video.preload = 'metadata'
      video.src = url
    })
  }
  finally {
    video.removeAttribute('src')
    video.load()
    URL.revokeObjectURL(url)
  }
}
