import { Buffer } from 'node:buffer'
import { execFileSync } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'
import process from 'node:process'
import { parseArgs } from 'node:util'
import { createUserDataExport, normalizeUserData } from '../apps/web/app/utils/userData.ts'

const legacyPackage = 'cn.yunyoujun.cook'
const legacyOrigins = ['https://localhost', 'https://cook.yunyoujun.cn']
const maxFavorites = 5000
const maxHistories = 500
const maxDocumentBytes = 256 * 1024

function readFavorite(entry) {
  const { id, time } = typeof entry === 'number' ? { id: entry, time: 0 } : entry ?? {}
  if (!Number.isSafeInteger(id) || id < 0 || !Number.isSafeInteger(time) || time < 0)
    throw new Error('旧版收藏的编号或时间格式无效')
  return { id, time }
}

// This function runs inside the legacy WebView. Keep it independent of Node and CLI state.
async function readLegacyLibrary(origins, favoriteLimit, historyLimit) {
  if (!origins.includes(location.origin))
    throw new Error('Unexpected WebView origin')
  const favorites = JSON.parse(localStorage.getItem('cook:favorites') ?? '[]')
  const histories = JSON.parse(localStorage.getItem('cook:history') ?? '[]')
  if (!Array.isArray(favorites) || !Array.isArray(histories))
    throw new TypeError('Invalid legacy lists')
  if (favorites.length > favoriteLimit || histories.length > historyLimit)
    throw new Error('Legacy lists exceed the import limits')
  if (!favorites.length)
    return { favorites, histories, recipes: [] }

  const databases = await indexedDB.databases()
  if (!databases.some(database => database.name === 'cook-db'))
    throw new Error('Legacy catalog is unavailable')
  const requestResult = request => new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = reject
  })
  const open = indexedDB.open('cook-db')
  open.onupgradeneeded = () => open.transaction.abort()
  const database = await requestResult(open)
  try {
    const store = database.transaction('recipes', 'readonly').objectStore('recipes')
    const ids = new Set(favorites.map(entry => typeof entry === 'number' ? entry : entry?.id))
    const recipes = await Promise.all([...ids].map(id => requestResult(store.get(id))))
    return { favorites, histories, recipes: recipes.filter(Boolean) }
  }
  finally {
    database.close()
  }
}

function recipeIdentity({ name, link, bv }) {
  if (name && (link || bv))
    return JSON.stringify([name, link || bv])
}

export function convertLegacyLibrary({ favorites, histories, recipes }, catalog, exportedAt = Date.now()) {
  if (favorites.length > maxFavorites || histories.length > maxHistories)
    throw new Error('源记录超出 Android 单文件导入上限（5,000 条收藏或 500 条浏览历史）')
  const currentIdsByIdentity = new Map()
  for (const recipe of catalog) {
    const identity = recipeIdentity(recipe)
    if (identity && Number.isInteger(recipe.id) && recipe.id >= 0 && recipe.id <= 2147483647) {
      if (!currentIdsByIdentity.has(identity))
        currentIdsByIdentity.set(identity, [])
      currentIdsByIdentity.get(identity).push(recipe.id)
    }
  }

  const oldById = new Map(recipes.map(recipe => [recipe.id, recipe]))
  const matched = new Map()
  let unmatched = 0
  for (const entry of favorites) {
    const { id, time } = readFavorite(entry)
    const oldRecipe = oldById.get(id)
    const candidates = oldRecipe && currentIdsByIdentity.get(recipeIdentity(oldRecipe))
    if (candidates?.length !== 1) {
      unmatched++
      continue
    }
    const currentId = candidates[0]
    matched.set(currentId, Math.max(time, matched.get(currentId) ?? 0))
  }
  const data = normalizeUserData({
    favorites: [...matched].map(([id, time]) => ({ id, time })),
    histories,
  })
  if (histories.some(entry => !Number.isSafeInteger(entry?.time)) || data.histories.length !== histories.length)
    throw new Error('旧版浏览历史的菜谱或时间格式无效')

  // Android validates the encoded document again with defaults and floating-point timestamps.
  const androidHistories = data.histories.map(({ recipe, time }) => ({
    recipe: { link: null, bv: null, difficulty: null, emojis: null, tags: null, methods: null, ...recipe },
    time,
  }))
  const backup = createUserDataExport(data, exportedAt)
  const { app: _app, exportedAt: _exportedAt, ...document } = backup
  const androidBytes = Buffer.byteLength(JSON.stringify({ ...document, histories: androidHistories }))
    + 2 * (document.favorites.length + document.histories.length + 1)
  if (androidBytes > maxDocumentBytes)
    throw new Error('源记录超出 Android 单文件导入大小上限')
  return { backup, unmatched }
}

async function evaluateWebView(url) {
  const socket = new WebSocket(url)
  try {
    return await new Promise((resolve, reject) => {
      const timeout = AbortSignal.timeout(10_000)
      timeout.addEventListener('abort', () => reject(new Error('读取旧版 WebView 超时')), { once: true })
      const fail = () => reject(new Error('无法连接旧版 WebView，请将旧版 App 保持在前台'))
      socket.addEventListener('error', fail, { once: true })
      socket.addEventListener('close', fail, { once: true })
      socket.addEventListener('open', () => {
        socket.send(JSON.stringify({
          id: 1,
          method: 'Runtime.evaluate',
          params: {
            expression: `(${readLegacyLibrary})(${JSON.stringify(legacyOrigins)}, ${maxFavorites}, ${maxHistories})`,
            awaitPromise: true,
            returnByValue: true,
          },
        }))
      }, { once: true })
      socket.addEventListener('message', ({ data }) => {
        let message
        try {
          message = JSON.parse(String(data))
        }
        catch (error) {
          reject(error)
          return
        }
        if (message.id !== 1)
          return
        const result = message.result?.result?.value
        if (!result)
          reject(new Error('无法读取旧版记录，请确认旧版目录加载完成，且数据未超过单文件导入上限'))
        else
          resolve(result)
      })
    })
  }
  finally {
    socket.close()
  }
}

function parseUrlOrigin(url) {
  try {
    return new URL(url).origin
  }
  catch {
    return null
  }
}

export function selectLegacyWebViewTarget(targets, local) {
  const matched = targets.filter(target => target?.type === 'page'
    && legacyOrigins.includes(parseUrlOrigin(target.url))
    && target.webSocketDebuggerUrl?.startsWith(`ws://${local}/devtools/page/`))
  if (matched.length !== 1)
    throw new Error('请回到旧版食用手册首页，关闭 App 内其他页面后再试')
  return matched[0]
}

async function readDeviceLibrary(serial) {
  const adb = (...args) => execFileSync('adb', ['-s', serial, ...args], { encoding: 'utf8', timeout: 15_000, stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  let pid
  try {
    pid = adb('shell', 'pidof', legacyPackage)
  }
  catch {
    throw new Error('未找到正在运行的旧版食用手册，请检查设备并打开旧版 App')
  }
  if (!/^\d+$/.test(pid))
    throw new Error('无法识别唯一的旧版 App 进程')
  const port = adb('forward', 'tcp:0', `localabstract:webview_devtools_remote_${pid}`)
  if (!/^\d+$/.test(port))
    throw new Error('无法建立旧版 WebView 调试转发端口')
  try {
    const local = `127.0.0.1:${port}`
    const response = await fetch(`http://${local}/json/list`, { signal: AbortSignal.timeout(5000) })
      .catch(() => { throw new Error('无法连接旧版 WebView，请确认旧版支持 WebView 调试') })
    if (!response.ok)
      throw new Error('旧版 WebView 调试接口不可用')
    const target = selectLegacyWebViewTarget(await response.json(), local)
    return await evaluateWebView(target.webSocketDebuggerUrl)
  }
  finally {
    try {
      adb('forward', '--remove', `tcp:${port}`)
    }
    catch {
      // ignore cleanup failures
    }
  }
}

async function main() {
  const { values } = parseArgs({
    options: {
      serial: { type: 'string', short: 's' },
      output: { type: 'string', short: 'o' },
      catalog: { type: 'string' },
      help: { type: 'boolean', short: 'h' },
    },
  })
  const usage = 'node scripts/export-legacy-android-library.mjs --serial SERIAL --output BACKUP.json [--catalog recipe.json]'
  if (values.help) {
    console.log(usage)
    return
  }
  if (!values.serial?.trim() || !values.output?.trim())
    throw new Error(usage)

  const catalogPath = values.catalog ?? new URL('../packages/data/recipe.json', import.meta.url)
  const catalog = JSON.parse(await readFile(catalogPath, 'utf8'))
  if (!Array.isArray(catalog) || !catalog.length)
    throw new Error('目标菜谱目录无效，请先生成或提供目标版本的 recipe.json')
  const { backup, unmatched } = convertLegacyLibrary(await readDeviceLibrary(values.serial), catalog)
  if (backup.favorites.length + backup.histories.length === 0)
    throw new Error(`没有可导出的收藏或浏览历史。无法匹配的旧收藏 ${unmatched} 条`)
  await writeFile(values.output, JSON.stringify(backup), { mode: 0o600, flag: 'wx' })
  console.log(`已导出 ${backup.favorites.length} 条收藏和 ${backup.histories.length} 条浏览历史。`)
  if (unmatched)
    console.warn(`${unmatched} 条旧收藏在现有目录中无法唯一匹配，仍可在旧版中查看。`)
}

if (import.meta.main) {
  try {
    await main()
  }
  catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
