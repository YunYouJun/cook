import type { RecipeItem } from '../../packages/cook/src/types/recipe'
import type { InstitutionalLowGIChineseReference } from '../../packages/cook/src/utils/reviewed-data'
import { createHash } from 'node:crypto'
import {
  dietaryRecipeFingerprint,
  institutionalLowGIAuthorityURL,
  institutionalLowGITag,
  institutionalLowGIChineseReferences as requiredChineseReferences,
} from '../../packages/cook/src/utils/reviewed-data'

export { institutionalLowGIAuthorityURL, institutionalLowGITag }
export type { InstitutionalLowGIChineseReference }

export interface InstitutionalLowGIEntry {
  recipeFingerprint: string
  sourceURL: string
  sourceRecipeTitle: string
  classificationTitle: string
  author: string
  reviewedAt: string
  requirements: string
  ingredients: string[]
  avoidIngredients: string[]
  unresolvedIngredients: string[]
  ingredientEvidence: string
  preparationEvidence: string
}

export interface InstitutionalLowGIData {
  schemaVersion: 1
  policyVersion: 1
  dataVersion: string
  publisher: 'GI News / University of Sydney'
  authorityURL: string
  claimKind: 'institutional-editorial'
  chineseReferences: InstitutionalLowGIChineseReference[]
  recipes: Record<string, InstitutionalLowGIEntry>
}

export function institutionalLowGIDataVersion(data: InstitutionalLowGIData): string {
  const { dataVersion: _version, ...content } = data
  return createHash('sha256').update(JSON.stringify(content)).digest('hex').slice(0, 12)
}

/** Manual source review is independent of the legacy nutrition labels and lab measurements. */
export function validateInstitutionalLowGIData(data: InstitutionalLowGIData, recipes: RecipeItem[]): string[] {
  const errors: string[] = []
  const chineseReferencesMatch = data.chineseReferences.length === requiredChineseReferences.length
    && requiredChineseReferences.every((required) => {
      const reference = data.chineseReferences.find(item => item.id === required.id)
      if (!reference)
        return false
      return reference.publisher === required.publisher
        && reference.title === required.title
        && reference.url === required.url
        && reference.scope === required.scope
    })
  if (data.schemaVersion !== 1 || data.policyVersion !== 1
    || data.publisher !== 'GI News / University of Sydney'
    || data.authorityURL !== institutionalLowGIAuthorityURL || data.claimKind !== 'institutional-editorial'
    || !chineseReferencesMatch) {
    errors.push('机构来源或审核政策不支持')
  }
  if (data.dataVersion !== institutionalLowGIDataVersion(data))
    errors.push('机构食谱版本与内容不一致')
  const identities = new Set<string>()
  const byID = new Map(recipes.map(recipe => [String(recipe.id), recipe]))
  for (const [id, entry] of Object.entries(data.recipes)) {
    const recipe = byID.get(id)
    if (!recipe || !recipe.tags?.includes(institutionalLowGITag)
      || recipe.link !== entry.sourceURL || recipe.bv
      || entry.recipeFingerprint !== dietaryRecipeFingerprint(recipe)) {
      errors.push(`${id}: 目录或来源已变化，需要重新审核`)
    }
    if (!/^https:\/\/glycemicindex\.com\/\d{4}\/\d{2}\/low-gi-recipes?-of-the-month(?:-\d+)?\/$/.test(entry.sourceURL)
      || !['Low GI Recipe of the Month', 'Low GI Recipes of the Month'].includes(entry.classificationTitle)) {
      errors.push(`${id}: 来源必须是已逐菜核对的 GI News 低 GI 食谱栏目`)
    }
    const date = new Date(`${entry.reviewedAt}T00:00:00Z`)
    if (Number.isNaN(date.valueOf()) || date.toISOString().slice(0, 10) !== entry.reviewedAt)
      errors.push(`${id}: 审核日期无效`)
    const identity = `${entry.sourceURL}\n${entry.sourceRecipeTitle}`
    if (identities.has(identity))
      errors.push(`${id}: 同一来源配方重复收录`)
    identities.add(identity)
    if (!entry.requirements.trim() || !entry.sourceRecipeTitle.trim() || !entry.author.trim()
      || !entry.ingredientEvidence.trim() || !entry.preparationEvidence.trim()
      || !entry.ingredients.length || !entry.avoidIngredients.length
      || recipe?.stuff.some(name => !entry.avoidIngredients.includes(name))) {
      errors.push(`${id}: 配方要求、内容依据或主料忌口映射不完整`)
    }
  }
  for (const recipe of recipes) {
    if (recipe.tags?.includes(institutionalLowGITag) && !data.recipes[String(recipe.id)])
      errors.push(`${recipe.id}: 机构标签缺少独立审核记录`)
  }
  if (Object.keys(data.recipes).length < 10)
    errors.push('机构筛选至少需要 10 道有效核对配方')
  return errors
}
