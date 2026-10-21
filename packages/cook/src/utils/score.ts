import type { RecipeItem } from '../types/recipe'

/**
 * 视频巡检时记下的 B 站数据（packages/data/video-stats.json），由 `pnpm check:videos --stats` 更新
 */
export interface VideoStat {
  view: number
  like: number
  favorite: number
  coin: number
  /** 时长，秒 */
  duration: number
}

export interface VideoStatsFile {
  updatedAt: string
  /** 视频号 → 数据；失效的视频记为 { dead: true } */
  videos: Record<string, VideoStat | { dead: true }>
}

/** 没有视频数据的菜（只有图文、或还没巡检到）按中间分算，不吃亏也不占便宜 */
export const DEFAULT_RECIPE_SCORE = 50

/** 点赞 + 收藏 + 投币占播放的比例到这个值就算满分，B 站做菜视频一般在 5%～15% */
const FULL_ENGAGEMENT = 0.15
/** 超过这个时长（秒）的视频跟着做不方便，扣 LONG_VIDEO_PENALTY 分 */
const LONG_VIDEO_SECONDS = 15 * 60
const LONG_VIDEO_PENALTY = 10

function clamp(value: number) {
  return Math.min(Math.max(value, 0), 1)
}

/**
 * 视频质量分，0～100 的整数：互动率占 6 成，播放量（取对数，1 千 → 0，1 千万 → 满）占 4 成。
 * 互动率为主，免得老视频只靠播放量一直排在前面；超过 15 分钟的扣 10 分。
 * 整数方便 Web 和 iOS 排序结果完全一致。
 */
export function videoScore(stat: VideoStat): number {
  const view = Math.max(stat.view, 1)
  const popularity = clamp((Math.log10(view) - 3) / 4)
  const engagement = clamp((stat.like + stat.favorite + stat.coin) / view / FULL_ENGAGEMENT)
  const penalty = stat.duration > LONG_VIDEO_SECONDS ? LONG_VIDEO_PENALTY : 0
  return Math.max(Math.round(100 * (0.4 * popularity + 0.6 * engagement)) - penalty, 0)
}

/**
 * 给菜谱写上质量分：有视频数据的按视频算，视频已失效的记 0，其余不写（排序时按 DEFAULT_RECIPE_SCORE）。
 * 只在 `pnpm convert` 和 `pnpm fixtures` 时调用，两端运行时只读 score 字段。
 */
export function applyRecipeScores<T extends RecipeItem>(recipes: T[], stats?: VideoStatsFile): T[] {
  if (!stats)
    return recipes
  return recipes.map((recipe) => {
    const stat = recipe.bv ? stats.videos[recipe.bv] : undefined
    if (!stat)
      return recipe
    return { ...recipe, score: 'dead' in stat ? 0 : videoScore(stat) }
  })
}
