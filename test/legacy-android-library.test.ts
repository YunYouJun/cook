import { describe, expect, it } from 'vitest'
import { parseUserDataExport } from '../apps/web/app/utils/userData'
import { convertLegacyLibrary, selectLegacyWebViewTarget } from '../scripts/export-legacy-android-library.mjs'

describe('legacy Android library export', () => {
  it('resolves old positions by recipe identity and preserves history snapshots', () => {
    const oldRecipe = { id: 1, name: '蒸蛋羹', bv: 'BV1Egg', stuff: ['鸡蛋'] }
    const visit = { time: 123, recipe: { ...oldRecipe, id: 99 } }
    const { backup, unmatched } = convertLegacyLibrary({
      favorites: [1, { id: 1, time: 123 }, { id: 9, time: 456 }, 10],
      histories: [visit],
      recipes: [oldRecipe, { id: 9, name: '番茄炒蛋', bv: 'BV1Tomato' }, { id: 10, name: '清蒸鱼', link: 'https://example.test/fish' }],
    }, [
      { id: 1, name: '错位菜谱', bv: 'BV1Other' },
      { ...oldRecipe, id: 80 },
      { id: 90, name: '清蒸鱼', link: 'https://example.test/fish' },
      { id: 91, name: '番茄炒蛋', bv: 'BV1Tomato' },
      { id: 92, name: '番茄炒蛋', bv: 'BV1Tomato' },
    ], 789)

    expect(unmatched).toBe(1)
    expect(backup.exportedAt).toBe(789)
    expect(parseUserDataExport(JSON.stringify(backup))).toMatchObject({
      favorites: [{ id: 80, time: 123 }, { id: 90, time: 0 }],
      histories: [{ time: 123, recipe: { name: '蒸蛋羹', bv: 'BV1Egg', stuff: ['鸡蛋'] } }],
      favoriteRemovals: [],
      historyRemovals: [],
      historyClearedAt: 0,
    })
    expect(backup.histories[0]?.recipe).not.toHaveProperty('id')
  })

  it('reports a missing legacy catalog row without assigning its ID to an unrelated recipe', () => {
    const { backup, unmatched } = convertLegacyLibrary({
      favorites: [65],
      histories: [],
      recipes: [],
    }, [{ id: 65, name: '面条', bv: 'BV1Noodles' }])

    expect(backup.favorites).toEqual([])
    expect(unmatched).toBe(1)
  })

  it('rejects malformed or oversized histories before writing a partial backup', () => {
    const source = { favorites: [], histories: [{ time: -1, recipe: { name: '清蒸鱼' } }], recipes: [] }
    expect(() => convertLegacyLibrary(source, [])).toThrow('旧版浏览历史')
    expect(() => convertLegacyLibrary({
      ...source,
      histories: [{ time: 123, recipe: { name: '清蒸鱼', stuff: ['鱼'.repeat(90000)] } }],
    }, [])).toThrow('单文件导入大小上限')
  })

  it('ignores blank or malformed WebView target URLs when selecting the Cook page', () => {
    const valid = {
      type: 'page',
      url: 'https://cook.yunyoujun.cn/',
      webSocketDebuggerUrl: 'ws://127.0.0.1:9222/devtools/page/1',
    }
    const target = selectLegacyWebViewTarget([
      { type: 'page', url: '', webSocketDebuggerUrl: 'ws://127.0.0.1:9222/devtools/page/blank' },
      { type: 'page', url: 'not-a-valid-url', webSocketDebuggerUrl: 'ws://127.0.0.1:9222/devtools/page/bad' },
      { type: 'page', url: 'https://evil.example/', webSocketDebuggerUrl: 'ws://127.0.0.1:9222/devtools/page/evil' },
      { type: 'page', url: 'https://cook.yunyoujun.cn/', webSocketDebuggerUrl: 'ws://127.0.0.1:9999/devtools/page/wrong-port' },
      valid,
    ], '127.0.0.1:9222')
    expect(target).toBe(valid)
  })
})
