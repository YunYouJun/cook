import type { Table } from 'dexie'
import type { RecipeItem } from '~/types'

import Dexie from 'dexie'

export interface DbRecipeItem extends RecipeItem {
  id?: number
}

interface RecipeMetaBase {
  total: number
  version: number
}

interface RecipeMetaSingle extends RecipeMetaBase {
  chunked: false
}

interface RecipeMetaChunked extends RecipeMetaBase {
  chunked: true
  chunkSize: number
  chunks: { index: number, hash: string, count: number }[]
}

type RecipeMeta = RecipeMetaSingle | RecipeMetaChunked

const CHUNK_HASH_STORAGE_KEY = 'cook:chunk-hashes'

// 预注册分片文件，Vite 会在构建时处理这些 glob 导入
const chunkModules = import.meta.glob<{ default: RecipeItem[] }>('../data/recipe-chunk-*.json')

export class MySubClassedDexie extends Dexie {
  recipes!: Table<DbRecipeItem>

  constructor() {
    super('cook-db')
    this.version(1).stores({
      recipes: '++id, name, stuff, bv, difficulty, tags, methods, tools, link, description', // Primary key and indexed props
    })
  }
}

export const db = new MySubClassedDexie()

/**
 * 加载已记录的分片 hash（用于增量更新判断）
 */
function loadChunkHashes(): Record<string, string> {
  try {
    const raw = localStorage.getItem(CHUNK_HASH_STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  }
  catch {
    return {}
  }
}

/**
 * 保存分片 hash 记录
 */
function saveChunkHashes(hashes: Record<string, string>) {
  localStorage.setItem(CHUNK_HASH_STORAGE_KEY, JSON.stringify(hashes))
}

/**
 * 单文件模式：全量加载 recipe.json
 */
async function loadSingleFile() {
  const { default: recipeData } = await import('../data/recipe.json')

  return db.recipes.bulkPut(
    (recipeData as RecipeItem[]).map((item, i) => ({
      id: i,
      ...item,
    })),
  )
}

/**
 * 分片模式：增量加载变化的分片
 */
async function loadChunks(meta: RecipeMetaChunked) {
  const savedHashes = loadChunkHashes()
  const newHashes = { ...savedHashes }

  // 找出需要加载的分片（hash 变化或新增的）
  const chunksToLoad = meta.chunks.filter(
    chunk => savedHashes[String(chunk.index)] !== chunk.hash,
  )

  if (chunksToLoad.length === 0) {
    return
  }

  // 并行加载所有需要更新的分片
  await Promise.all(
    chunksToLoad.map(async (chunk) => {
      const modulePath = `../data/recipe-chunk-${chunk.index}.json`
      const loader = chunkModules[modulePath]
      if (!loader) {
        console.warn(`Chunk module not found: ${modulePath}`)
        return
      }

      const { default: chunkData } = await loader()
      const offset = chunk.index * meta.chunkSize

      await db.recipes.bulkPut(
        (chunkData as RecipeItem[]).map((item, i) => ({
          id: offset + i,
          ...item,
        })),
      )

      newHashes[String(chunk.index)] = chunk.hash
    }),
  )

  saveChunkHashes(newHashes)
}

export async function initDb() {
  const { default: meta } = await import('../data/recipe-meta.json') as { default: RecipeMeta }

  if (meta.chunked) {
    return loadChunks(meta)
  }
  else {
    return loadSingleFile()
  }
}
