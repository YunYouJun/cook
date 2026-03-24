import process from 'node:process'
import * as lark from '@larksuiteoapi/node-sdk'
import consola from 'consola'
import { FEISHU_SHEET_ID, FEISHU_WIKI_NODE_TOKEN } from './config.js'

export interface FeishuCredentials {
  appId: string
  appSecret: string
}

/**
 * 校验飞书环境变量
 */
export function getFeishuCredentials(): FeishuCredentials {
  const appId = process.env.FEISHU_APP_ID
  const appSecret = process.env.FEISHU_APP_SECRET

  if (!appId || !appSecret) {
    consola.error('缺少飞书应用凭证，请在 .env 文件中配置：')
    consola.error('  FEISHU_APP_ID=<你的应用 ID>')
    consola.error('  FEISHU_APP_SECRET=<你的应用密钥>')
    consola.error('')
    consola.error('获取方式：https://open.feishu.cn/app → 创建/选择应用 → 凭证与基础信息')
    process.exit(1)
  }

  return { appId, appSecret }
}

/**
 * 创建飞书 Client
 */
export function createFeishuClient(credentials: FeishuCredentials): lark.Client {
  return new lark.Client({
    appId: credentials.appId,
    appSecret: credentials.appSecret,
    appType: lark.AppType.SelfBuild,
    domain: lark.Domain.Feishu,
  })
}

/**
 * 从 Wiki 节点获取 spreadsheetToken（obj_token）
 */
export async function getSpreadsheetToken(client: lark.Client): Promise<string> {
  const res = await client.wiki.v2.space.getNode({
    params: {
      token: FEISHU_WIKI_NODE_TOKEN,
    },
  })

  if (res.code !== 0) {
    throw new Error(`获取 Wiki 节点信息失败: [${res.code}] ${res.msg}`)
  }

  const objToken = res.data?.node?.obj_token
  if (!objToken) {
    throw new Error('Wiki 节点返回的 obj_token 为空')
  }

  consola.info(`Wiki 节点类型: ${res.data?.node?.obj_type}, 标题: ${res.data?.node?.title}`)
  return objToken
}

/**
 * 读取电子表格数据
 * 使用 sheets v2 API（需要 client.request 手动调用）
 */
export async function readSpreadsheetValues(
  client: lark.Client,
  spreadsheetToken: string,
): Promise<(string | null)[][]> {
  // 使用 sheetId 作为 range，读取该 sheet 的全部数据
  const range = FEISHU_SHEET_ID

  const res = await client.request<any>({
    method: 'GET',
    url: `/open-apis/sheets/v2/spreadsheets/${spreadsheetToken}/values/${range}`,
    params: {
      valueRenderOption: 'ToString',
      dateTimeRenderOption: 'FormattedString',
    },
  })

  if (res.code !== 0) {
    throw new Error(`读取电子表格数据失败: [${res.code}] ${res.msg}`)
  }

  const values = res.data?.valueRange?.values
  if (!values || values.length === 0) {
    throw new Error('电子表格数据为空')
  }

  consola.info(`读取到 ${values.length} 行数据（含表头）`)
  return values
}
