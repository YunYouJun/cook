export type VideoStatus = 'ok' | 'dead' | 'unknown'

/**
 * 根据 B 站 view 接口返回的 code 判断视频状态
 * -404 不存在，62002 稿件不可见，62012 仅 UP 主自己可见；
 * 其余（如 -412 风控、62004 审核中）无法确定，不当作失效
 */
export function classifyBilibiliCode(code: number): VideoStatus {
  if (code === 0)
    return 'ok'
  if (code === -404 || code === 62002 || code === 62012)
    return 'dead'
  return 'unknown'
}

/** 视频号对应的 view 接口地址，支持 BV 号和 av 号 */
export function bilibiliViewApi(id: string) {
  const query = id.startsWith('av') ? `aid=${id.slice(2)}` : `bvid=${id}`
  return `https://api.bilibili.com/x/web-interface/view?${query}`
}
