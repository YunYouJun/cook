import type { CookCatalogData } from '../utils/agent-catalog.js'
import type { RecommendationMode } from '../utils/recommend.js'
import process from 'node:process'
import readline from 'node:readline'
import { version } from '../../package.json'
import { loadCookCatalog, queryCookCatalog } from '../utils/agent-catalog.js'
import { findIncompatibleRules } from '../utils/incompatible.js'
import { parseCookQuery } from '../utils/query.js'
import { uniqueStuff } from '../utils/recommend.js'
import { lookupShelfLife } from '../utils/shelf-life.js'

export interface JsonRpcRequest {
  jsonrpc: '2.0'
  id?: string | number | null
  method: string
  params?: Record<string, unknown>
}

export interface JsonRpcResponse {
  jsonrpc: '2.0'
  id: string | number | null
  result?: unknown
  error?: {
    code: number
    message: string
  }
}

const SUPPORTED_PROTOCOL_VERSION = '2024-11-05'

const MCP_TOOLS = [
  {
    name: 'search_recipes',
    description: '检索食用手册（Cook）中文菜谱库，支持自然语言整句（如"冰箱里有土豆和鸡蛋，不要辣，用电饭煲"）或结构化食材/厨具/忌口/做法筛选，返回匹配菜谱、缺失食材、B站视频链接与食物相克提醒。',
    inputSchema: {
      type: 'object',
      properties: {
        query: {
          type: 'string',
          description: '自然语言一句话描述，如 "冰箱里有番茄和鸡蛋，不要辣"',
        },
        stuff: {
          type: 'array',
          items: { type: 'string' },
          description: '已有食材列表（支持常见别名，如 西红柿→番茄）',
        },
        avoid: {
          type: 'array',
          items: { type: 'string' },
          description: '忌口或不想用的食材列表',
        },
        tool: {
          type: 'string',
          description: '可用厨具（如 电饭煲、空气炸锅、微波炉、烤箱、一口大锅）',
        },
        difficulty: {
          type: 'string',
          enum: ['简单', '普通', '困难'],
          description: '菜谱难度',
        },
        method: {
          type: 'string',
          description: '烹饪方式（如 炒、蒸、煮、烤、炸、炖）',
        },
        tag: {
          type: 'string',
          description: '菜谱标签（如 懒人、下饭、汤）',
        },
        noSpicy: {
          type: 'boolean',
          description: '是否排除辣菜',
        },
        mode: {
          type: 'string',
          enum: ['loose', 'strict', 'survival'],
          description: '推荐模式：loose（宽松匹配，默认）、strict（包含全部已选食材）、survival（仅用已有食材和常备调料就能做）',
        },
        limit: {
          type: 'integer',
          minimum: 1,
          maximum: 50,
          description: '最多返回菜谱数（默认 10）',
        },
      },
    },
  },
  {
    name: 'parse_cook_query',
    description: '将中文做菜自然语言（如"冰箱剩土豆和鸡翅，香菜不要，想用空气炸锅做个简单的"）离线解析为结构化食材、忌口、厨具、难度与做法条件。',
    inputSchema: {
      type: 'object',
      required: ['query'],
      properties: {
        query: {
          type: 'string',
          description: '用户的中文描述句子',
        },
      },
    },
  },
  {
    name: 'check_shelf_life',
    description: '查询常见食材在冰箱冷藏室的建议保质天数（支持别名标准化），用于冰箱临期清理和饮食规划。',
    inputSchema: {
      type: 'object',
      required: ['ingredients'],
      properties: {
        ingredients: {
          type: 'array',
          items: { type: 'string' },
          description: '食材名称列表（如 ["西红柿", "鸡蛋", "菠菜"]）',
        },
      },
    },
  },
  {
    name: 'check_incompatible_foods',
    description: '检查一组食材之间是否存在食用手册收录的食物相克提醒（支持常见别名）。',
    inputSchema: {
      type: 'object',
      required: ['ingredients'],
      properties: {
        ingredients: {
          type: 'array',
          items: { type: 'string' },
          description: '食材名称列表（至少 2 种）',
        },
      },
    },
  },
] as const

function asStringArray(value: unknown): string[] | undefined {
  if (Array.isArray(value))
    return value.map(item => String(item)).filter(Boolean)
  if (typeof value === 'string' && value.trim())
    return value.split(/[,，、;；\n]/).map(item => item.trim()).filter(Boolean)
  return undefined
}

function asMode(value: unknown): RecommendationMode | undefined {
  if (value === 'loose' || value === 'strict' || value === 'survival')
    return value
  return undefined
}

function toolJsonResult(payload: unknown, isError = false) {
  return {
    content: [
      {
        type: 'text',
        text: JSON.stringify(payload, null, 2),
      },
    ],
    ...(isError ? { isError: true } : {}),
  }
}

export function createCookMcpHandler(catalog: CookCatalogData) {
  return function handleMessage(message: JsonRpcRequest): JsonRpcResponse | null {
    const hasId = message.id !== undefined
    const id = message.id ?? null

    // JSON-RPC notifications (no id) must not receive a response
    if (!hasId)
      return null

    switch (message.method) {
      case 'initialize': {
        const requestedProtocol = typeof message.params?.protocolVersion === 'string'
          ? message.params.protocolVersion
          : SUPPORTED_PROTOCOL_VERSION
        return {
          jsonrpc: '2.0',
          id,
          result: {
            protocolVersion: requestedProtocol || SUPPORTED_PROTOCOL_VERSION,
            capabilities: {
              tools: {},
            },
            serverInfo: {
              name: '@yunyoujun/cook',
              version,
            },
          },
        }
      }

      case 'ping':
        return {
          jsonrpc: '2.0',
          id,
          result: {},
        }

      case 'tools/list':
        return {
          jsonrpc: '2.0',
          id,
          result: {
            tools: MCP_TOOLS,
          },
        }

      case 'tools/call': {
        const name = typeof message.params?.name === 'string' ? message.params.name : ''
        const args = (message.params?.arguments && typeof message.params.arguments === 'object')
          ? message.params.arguments as Record<string, unknown>
          : {}

        switch (name) {
          case 'search_recipes': {
            const limitRaw = typeof args.limit === 'number' ? Math.trunc(args.limit) : 10
            const limit = Math.min(Math.max(limitRaw, 1), 50)
            const data = queryCookCatalog(catalog, {
              query: typeof args.query === 'string' ? args.query : undefined,
              stuff: asStringArray(args.stuff),
              avoid: asStringArray(args.avoid),
              tool: typeof args.tool === 'string' ? args.tool : undefined,
              difficulty: typeof args.difficulty === 'string' ? args.difficulty : undefined,
              method: typeof args.method === 'string' ? args.method : undefined,
              tag: typeof args.tag === 'string' ? args.tag : undefined,
              noSpicy: typeof args.noSpicy === 'boolean' ? args.noSpicy : undefined,
              mode: asMode(args.mode),
              limit,
            })
            return {
              jsonrpc: '2.0',
              id,
              result: toolJsonResult(data),
            }
          }

          case 'parse_cook_query': {
            const query = typeof args.query === 'string' ? args.query : ''
            return {
              jsonrpc: '2.0',
              id,
              result: toolJsonResult(parseCookQuery(query)),
            }
          }

          case 'check_shelf_life': {
            const ingredients = asStringArray(args.ingredients) ?? []
            return {
              jsonrpc: '2.0',
              id,
              result: toolJsonResult(lookupShelfLife(ingredients)),
            }
          }

          case 'check_incompatible_foods': {
            const ingredients = uniqueStuff(asStringArray(args.ingredients) ?? [])
            const warnings = findIncompatibleRules(ingredients, catalog.incompatible)
            return {
              jsonrpc: '2.0',
              id,
              result: toolJsonResult({ ingredients, warnings }),
            }
          }

          default:
            return {
              jsonrpc: '2.0',
              id,
              result: toolJsonResult({ error: `Unknown tool: ${name}` }, true),
            }
        }
      }

      default:
        return {
          jsonrpc: '2.0',
          id,
          error: {
            code: -32601,
            message: `Method not found: ${message.method}`,
          },
        }
    }
  }
}

export function runMcpServer() {
  const catalog = loadCookCatalog()
  const handle = createCookMcpHandler(catalog)

  const rl = readline.createInterface({
    input: process.stdin,
    crlfDelay: Infinity,
  })

  rl.on('line', (line) => {
    const trimmed = line.trim()
    if (!trimmed)
      return

    let request: JsonRpcRequest
    try {
      request = JSON.parse(trimmed) as JsonRpcRequest
    }
    catch {
      const parseError: JsonRpcResponse = {
        jsonrpc: '2.0',
        id: null,
        error: {
          code: -32700,
          message: 'Parse error',
        },
      }
      process.stdout.write(`${JSON.stringify(parseError)}\n`)
      return
    }

    const response = handle(request)
    if (response)
      process.stdout.write(`${JSON.stringify(response)}\n`)
  })
}
