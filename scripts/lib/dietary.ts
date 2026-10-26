import type { RecipeItem } from '../../packages/cook/src/types/recipe'
import type { RecipeText } from '../../packages/cook/src/types/recipe-text'
import type { AmountAnchor } from './recipe-amounts'
import { Buffer } from 'node:buffer'
import { createHash } from 'node:crypto'
import { sourceFingerprint, sourceLines } from './recipe-amounts'

export const dietaryGoals = ['weight-aware', 'less-added-sugar', 'balanced-pairing'] as const
export type DietaryGoalID = typeof dietaryGoals[number]

export interface DietaryAssessment {
  status: 'pass' | 'fail' | 'unknown'
  reason: string
  evidence: AmountAnchor[]
}

export interface DietaryEntry {
  recipeFingerprint: string
  sourceLink: string
  sourceFingerprint: string
  reviewedAt: string
  ingredients: string[]
  avoidIngredients: string[]
  unresolvedIngredients: string[]
  ingredientEvidence: AmountAnchor[]
  foodGroups: ('staple' | 'protein' | 'vegetables')[]
  assessments: Record<DietaryGoalID, DietaryAssessment>
  portionOil?: {
    referenceServings: number
    batchOilGrams: { lower: number, upper: number }
    servingEvidence: AmountAnchor[]
    oilEvidence: AmountAnchor[]
  }
}

export interface DietaryData {
  schemaVersion: 1
  policyVersion: 1
  dataVersion: string
  amountsFingerprint: string
  enabledGoals: DietaryGoalID[]
  recipes: Record<string, DietaryEntry>
}

/** Bind annotations to recipe identity and preparation metadata, independently of display scores. */
export function dietaryRecipeFingerprint(recipe: RecipeItem): string {
  const tokens = [String(recipe.id), recipe.name, recipe.link ?? '', recipe.bv ?? '', recipe.difficulty ?? '']
  for (const values of [recipe.stuff, recipe.tools, recipe.methods ?? [], recipe.tags ?? []])
    tokens.push(String(values.length), ...values)
  let hash = 14695981039346656037n
  for (const byte of Buffer.from(tokens.join('\0'), 'utf8'))
    hash = BigInt.asUintN(64, (hash ^ BigInt(byte)) * 1099511628211n)
  return hash.toString(16).padStart(16, '0')
}

/** Include every annotation field in its version without refreshing any reviewed source fingerprints. */
export function dietaryDataVersion(data: DietaryData): string {
  const { dataVersion: _version, ...content } = data
  return createHash('sha256').update(JSON.stringify(content)).digest('hex').slice(0, 12)
}

/** Verify source evidence and release coverage after JSON Schema validation. */
export function validateDietaryData(data: DietaryData, recipes: RecipeItem[], texts: Record<string, RecipeText>, amountsFingerprint: string): string[] {
  const errors: string[] = []
  const byID = new Map(recipes.map(recipe => [String(recipe.id), recipe]))
  if (data.dataVersion !== dietaryDataVersion(data))
    errors.push('饮食数据版本与内容不一致')
  if (data.amountsFingerprint !== amountsFingerprint)
    errors.push('用量标注已变化，需要核对后更新饮食数据')

  for (const [id, entry] of Object.entries(data.recipes)) {
    const recipe = byID.get(id)
    const source = texts[id]
    const fail = (message: string) => errors.push(`${id}: ${message}`)
    if (!recipe || !source) {
      fail('目录或图文来源不存在')
      continue
    }
    if (recipe.stuff.length >= 10)
      fail('首版不支持大杂烩')
    if (entry.recipeFingerprint !== dietaryRecipeFingerprint(recipe) || entry.sourceLink !== recipe.link)
      fail('菜谱字段已变化，需要重新审核')
    if (entry.sourceFingerprint !== sourceFingerprint(source))
      fail('图文已变化，需要重新审核')
    const lines = sourceLines(source)
    const matches = (anchor: AmountAnchor) => lines.some(line => line.block === anchor.block && line.item === anchor.item && line.text === anchor.text)
    if (!entry.ingredientEvidence.every(matches))
      fail('原料证据与原文不符')
    if (!entry.ingredients.every(name => entry.ingredientEvidence.some(anchor => anchor.text.includes(name))))
      fail('原料缺少对应证据')
    if (!entry.ingredients.every(name => entry.avoidIngredients.includes(name)) || !entry.unresolvedIngredients.every(name => entry.ingredients.includes(name)))
      fail('忌口映射或未知配料不完整')
    const date = new Date(`${entry.reviewedAt}T00:00:00Z`)
    if (Number.isNaN(date.valueOf()) || date.toISOString().slice(0, 10) !== entry.reviewedAt)
      fail('审核日期无效')
    for (const goal of dietaryGoals) {
      const assessment = entry.assessments[goal]
      if (!assessment.evidence.every(matches))
        fail(`${goal} 证据与原文不符`)
      if (assessment.status === 'pass') {
        const expected = { 'weight-aware': 'portion-oil-reference', 'less-added-sugar': 'no-added-sugar', 'balanced-pairing': 'food-group-pairing' }[goal]
        if (assessment.reason !== expected)
          fail(`${goal} 通过理由无效`)
        if (goal === 'less-added-sugar' && (entry.unresolvedIngredients.length || entry.ingredients.some(name => ['糖', '白糖', '冰糖', '砂糖', '糖浆', '蜂蜜'].includes(name))))
          fail('添加糖或未核对复合调料不能标为未添加糖')
        if (goal === 'weight-aware') {
          const oil = entry.portionOil
          if (!oil || oil.batchOilGrams.lower > oil.batchOilGrams.upper || !oil.servingEvidence.every(matches) || !oil.oilEvidence.every(matches))
            fail('份量、添加油数值或证据无效')
        }
      }
      else if (!['added-sugar', 'unverified-seasoning', 'unverified-portion-oil'].includes(assessment.reason)) {
        fail(`${goal} 未通过理由无效`)
      }
    }
  }
  for (const goal of data.enabledGoals) {
    const count = Object.values(data.recipes).filter(entry => entry.assessments[goal].status === 'pass').length
    if (count < 10)
      errors.push(`${goal}: 开放目标至少需要 10 道通过记录，当前 ${count}`)
  }
  const covered = Object.values(data.recipes).filter(entry => data.enabledGoals.some(goal => entry.assessments[goal].status === 'pass')).length
  if (data.enabledGoals.length && covered < 30)
    errors.push(`开放目标合计至少需要 30 道独立菜谱，当前 ${covered}`)
  return errors
}
