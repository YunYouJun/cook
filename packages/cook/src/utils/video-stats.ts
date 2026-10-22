import type { VideoStatsFile } from './score'
import fs from 'node:fs'
import { videoStatsJsonFile } from './config'

/** 读取 packages/data/video-stats.json；还没有这份数据时返回 undefined，菜谱就都不带质量分 */
export function readVideoStats(file = videoStatsJsonFile): VideoStatsFile | undefined {
  if (!fs.existsSync(file))
    return undefined
  return JSON.parse(fs.readFileSync(file, 'utf-8')) as VideoStatsFile
}
