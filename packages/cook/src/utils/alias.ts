import aliases from '../../../data/aliases.json'

/** 常见别名 → 数据库标准名，数据在 packages/data/aliases.json，Web 和 iOS 共用 */
export const STUFF_ALIASES: Record<string, string> = aliases

/**
 * 将用户输入的食材标准化为数据库中的名称
 */
export function normalizeStuff(input: string[]): string[] {
  return input.map((s) => {
    const trimmed = s.trim()
    return STUFF_ALIASES[trimmed] ?? trimmed
  })
}
