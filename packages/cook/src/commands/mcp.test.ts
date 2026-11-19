import type { CookCatalogData } from '../utils/agent-catalog.js'
import { describe, expect, it } from 'vitest'
import { createCookMcpHandler } from './mcp.js'

const catalog: CookCatalogData = {
  defaultPantry: ['油', '盐'],
  incompatible: [
    { foodA: '牛奶', foodB: '韭菜', reason: '牛奶与韭菜同食会影响钙的吸收' },
  ],
  recipes: [
    {
      id: 1,
      name: '番茄炒蛋',
      stuff: ['番茄', '鸡蛋'],
      bv: 'BV1rf4y1872R',
      difficulty: '简单',
      tags: ['家常菜'],
      methods: ['炒'],
      tools: ['一口大锅'],
    },
    {
      id: 2,
      name: '辣炒土豆丝',
      stuff: ['土豆', '辣椒'],
      difficulty: '普通',
      tags: ['辣'],
      methods: ['炒'],
      tools: ['一口大锅'],
    },
  ],
}

describe('createCookMcpHandler', () => {
  const handle = createCookMcpHandler(catalog)

  it('handles initialize and ignores notifications', () => {
    const init = handle({
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: { protocolVersion: '2024-11-05' },
    })
    expect(init?.result).toMatchObject({
      protocolVersion: '2024-11-05',
      serverInfo: { name: '@yunyoujun/cook' },
      capabilities: { tools: {} },
    })

    const notification = handle({
      jsonrpc: '2.0',
      method: 'notifications/initialized',
    })
    expect(notification).toBeNull()
  })

  it('lists all 4 cook tools', () => {
    const res = handle({ jsonrpc: '2.0', id: 2, method: 'tools/list' })
    const tools = (res?.result as { tools: { name: string }[] }).tools
    expect(tools.map(t => t.name)).toEqual([
      'search_recipes',
      'parse_cook_query',
      'check_shelf_life',
      'check_incompatible_foods',
    ])
  })

  it('executes search_recipes tool with natural language query', () => {
    const res = handle({
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/call',
      params: {
        name: 'search_recipes',
        arguments: { query: '有西红柿和鸡蛋，不要辣', limit: 5 },
      },
    })
    const content = (res?.result as { content: { text: string }[] }).content[0]!.text
    const parsed = JSON.parse(content)
    expect(parsed.recipes[0].name).toBe('番茄炒蛋')
    expect(parsed.parsedQuery.stuff).toEqual(['番茄', '鸡蛋'])
  })

  it('executes check_shelf_life and check_incompatible_foods tools', () => {
    const shelfRes = handle({
      jsonrpc: '2.0',
      id: 4,
      method: 'tools/call',
      params: {
        name: 'check_shelf_life',
        arguments: { ingredients: ['西红柿', '鸡蛋'] },
      },
    })
    const shelfPayload = JSON.parse((shelfRes?.result as { content: { text: string }[] }).content[0]!.text)
    expect(shelfPayload.items[0]).toMatchObject({ normalized: '番茄', days: 7 })

    const incompatRes = handle({
      jsonrpc: '2.0',
      id: 5,
      method: 'tools/call',
      params: {
        name: 'check_incompatible_foods',
        arguments: { ingredients: ['牛奶', '韭菜'] },
      },
    })
    const incompatPayload = JSON.parse((incompatRes?.result as { content: { text: string }[] }).content[0]!.text)
    expect(incompatPayload.warnings).toHaveLength(1)
  })

  it('returns JSON-RPC error for unknown method and tool error for unknown tool', () => {
    const unknownMethod = handle({ jsonrpc: '2.0', id: 6, method: 'unknown/method' })
    expect(unknownMethod?.error?.code).toBe(-32601)

    const unknownTool = handle({
      jsonrpc: '2.0',
      id: 7,
      method: 'tools/call',
      params: { name: 'nonexistent', arguments: {} },
    })
    expect((unknownTool?.result as { isError?: boolean }).isError).toBe(true)
  })
})
