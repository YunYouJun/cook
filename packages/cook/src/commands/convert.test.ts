import fs from 'node:fs'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { runConvert } from './convert'

const fixture = vi.hoisted(() => ({
  constants: `export const lastDbUpdated = '2000-01-01 00:00:00'
export const unchanged = true
`,
  recipes: 'name,stuff,bv,difficulty,tags,methods,tools,id,link\n土豆丝,土豆,,简单,,炒,一口大锅,1,',
  rules: 'foodA,foodB,reason\n牛奶,柠檬,测试规则',
}))

vi.mock('consola', () => ({ default: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), success: vi.fn() } }))
vi.mock('../utils/config.js', async importOriginal => ({
  ...await importOriginal<typeof import('../utils/config.js')>(),
  CHUNK_SIZE: 500,
  CHUNK_THRESHOLD: 1000,
  DATA_SCHEMA_VERSION: 1,
  incompatibleFoodsCsvFile: '/fixture/rules.csv',
  incompatibleFoodsJsonFile: '/fixture/rules.json',
  recipeCsvFile: '/fixture/recipes.csv',
  recipeJsonFile: '/fixture/recipes.json',
  recipeMetaJsonFile: '/fixture/meta.json',
  rootDir: '/fixture',
  sharedDataFiles: [],
  videoStatsJsonFile: '/fixture/video-stats.json',
}))
vi.mock('node:fs', () => ({
  default: {
    readFileSync: vi.fn((file: string) => {
      if (file === '/fixture/recipes.csv')
        return fixture.recipes
      if (file === '/fixture/rules.csv')
        return fixture.rules
      if (file === '/fixture/apps/web/app/constants/index.ts')
        return fixture.constants
      throw new Error('Unexpected fixture read')
    }),
    writeFileSync: vi.fn(),
    existsSync: vi.fn((file: string) => file === '/fixture/apps/web/app/constants/index.ts'),
    readdirSync: vi.fn(() => []),
  },
}))

beforeEach(() => vi.clearAllMocks())
afterEach(() => vi.unstubAllEnvs())

describe('convert source timestamp policy', () => {
  it('generates recipe artifacts while preserving tracked constants for an attested build', () => {
    vi.stubEnv('COOK_PRESERVE_SOURCE_TIMESTAMP', 'true')
    runConvert()
    const writes = vi.mocked(fs.writeFileSync).mock.calls
    expect(writes.map(([file]) => file)).toEqual(['/fixture/recipes.json', '/fixture/rules.json', '/fixture/meta.json'])
    expect(JSON.parse(String(writes[0]![1]))[0].name).toBe('土豆丝')
    expect(JSON.parse(String(writes[2]![1])).total).toBe(1)
  })

  it.each(['', 'false'])('keeps manual conversion timestamp updates when the internal flag is %j', (flag) => {
    vi.stubEnv('COOK_PRESERVE_SOURCE_TIMESTAMP', flag)
    runConvert()
    const writes = vi.mocked(fs.writeFileSync).mock.calls
    const constants = writes.find(([file]) => file === '/fixture/apps/web/app/constants/index.ts')
    expect(constants).toBeDefined()
    expect(String(constants![1])).not.toContain('2000-01-01 00:00:00')
    expect(String(constants![1])).toContain('export const unchanged = true')
    expect(writes.some(([file]) => file === '/fixture/meta.json')).toBe(true)
  })
})
