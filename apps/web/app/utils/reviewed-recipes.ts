import type { RecipeText, RecipeTextData } from '@yunyoujun/cook'
import type { DietaryGoalID, InstitutionalLowGIChineseReference } from '@yunyoujun/cook/reviewed-data'
import type { RecipeItem } from '~/types'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import {
  addedSugarIngredients,
  dietaryGoals,
  dietaryRecipeFingerprint,
  institutionalLowGIAuthorityURL,
  institutionalLowGIChineseReferences,
  institutionalLowGITag,
  recipeTextFingerprint,
} from '@yunyoujun/cook/reviewed-data'

export type { DietaryGoalID } from '@yunyoujun/cook/reviewed-data'

export type DiscoverySceneID = 'next-day-lunch' | 'institutional-low-gi'
export type ReviewStatus = 'pass' | 'fail' | 'unknown'

export interface ReviewAnchor {
  block: number
  item?: number
  text: string
}

export interface DietaryAssessment {
  status: ReviewStatus
  reason: string
  evidence: ReviewAnchor[]
}

export interface OilRange {
  lower: number
  upper: number
}

export interface DietaryEntry {
  recipeFingerprint: string
  sourceLink: string
  sourceFingerprint: string
  reviewedAt: string
  ingredients: string[]
  avoidIngredients: string[]
  unresolvedIngredients: string[]
  ingredientEvidence: ReviewAnchor[]
  foodGroups: ('staple' | 'protein' | 'vegetables')[]
  assessments: Record<DietaryGoalID, DietaryAssessment>
  portionOil?: {
    referenceServings: number
    batchOilGrams?: OilRange
    batchOilMilliliters?: OilRange
    servingEvidence: ReviewAnchor[]
    oilEvidence: ReviewAnchor[]
  }
}

export interface DietaryData {
  schemaVersion: 1 | 2
  policyVersion: 1 | 2
  dataVersion: string
  amountsFingerprint: string
  enabledGoals: DietaryGoalID[]
  recipes: Record<string, DietaryEntry>
}

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

export interface LunchReviewEntry {
  name: string
  sourceLink: string
  sourceFingerprint: string
  referenceServings: number
  mealRole: string
  servingEvidence: ReviewAnchor[]
  mainIngredientEvidence: ReviewAnchor[]
  addedOil: {
    lower: number
    upper: number
    unit: 'g' | 'ml'
    evidence: ReviewAnchor[]
    note?: string
  }
  packingNote?: string
}

export interface LunchReviewData {
  schemaVersion: 1
  reviewedAt: string
  source: RecipeTextData['source']
  tag: string
  scope: string
  storageGuide: string
  recipes: Record<string, LunchReviewEntry>
}

export interface ReviewAvailability {
  enabled: boolean
  count: number
  reason?: string
}

export interface ReviewedRecipeCatalog {
  goals: Record<DietaryGoalID, ReviewAvailability>
  scenes: Record<DiscoverySceneID, ReviewAvailability>
  dietaryEntries: Record<string, DietaryEntry>
  institutionalEntries: Record<string, InstitutionalLowGIEntry>
  lunchEntries: Record<string, LunchReviewEntry>
  textSourceCommit?: string
  lunchMeta?: Pick<LunchReviewData, 'scope' | 'storageGuide' | 'reviewedAt'>
  institutionalChineseReferences: InstitutionalLowGIChineseReference[]
  messages: {
    dietary?: string
    institutional?: string
    lunch?: string
  }
}

export interface ReviewedFilterOption<T extends string> {
  id: T
  label: string
  summary: string
  explanation: string
  count: number
  enabled: boolean
}

export const dietaryGoalDetails: Record<DietaryGoalID, { label: string, summary: string, explanation: string }> = {
  'weight-aware': {
    label: '控制体重',
    summary: '查看份量、添加油用量参考',
    explanation: '按原文核对份量和添加油，不代表低热量或低脂，也不计算每日能量。',
  },
  'less-added-sugar': {
    label: '减少添加糖',
    summary: '寻找做法中未额外添加糖的菜',
    explanation: '核对原料、调料及步骤中的添加糖。不表示总糖为零、低碳水、低 GI 或适合血糖管理。',
  },
  'balanced-pairing': {
    label: '均衡搭配',
    summary: '了解这道菜提供什么，还能搭配什么',
    explanation: '按做法中的食物类别给出搭配提示。单道菜不等于一餐营养齐全，仍需结合份量和整餐安排。',
  },
}

export const discoverySceneDetails: Record<DiscoverySceneID, { label: string, summary: string, explanation: string }> = {
  'next-day-lunch': {
    label: '次日便当',
    summary: '提前备好，明天带饭',
    explanation: '需快速冷却、冷藏运输和存放，次日彻底复热。份量和用油参考见图文做法。',
  },
  'institutional-low-gi': {
    label: '机构低 GI 食谱',
    summary: '按机构原方查看配方要求',
    explanation: '来源为悉尼大学 GI News 的低 GI 食谱栏目，属于机构食谱参考，未提供整道菜的实验室实测 GI 值。请按原方的食材品种、比例和做法制作；不表示无糖、低热量或适合个人疾病饮食。',
  },
}

const foodGroupLabels = {
  staple: '主食',
  protein: '蛋白质来源',
  vegetables: '蔬菜',
} as const

const allowedDietaryReasons: Record<DietaryGoalID, { pass: string, other: string[] }> = {
  'weight-aware': { pass: 'portion-oil-reference', other: ['added-sugar', 'unverified-seasoning', 'unverified-portion-oil'] },
  'less-added-sugar': { pass: 'no-added-sugar', other: ['added-sugar', 'unverified-seasoning', 'unverified-portion-oil'] },
  'balanced-pairing': { pass: 'food-group-pairing', other: ['added-sugar', 'unverified-seasoning', 'unverified-portion-oil'] },
}

function isRecord(value: unknown): value is Record<string, any> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isValidDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value))
    return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.valueOf()) && date.toISOString().slice(0, 10) === value
}

function isValidRange(value: unknown): value is OilRange {
  return isRecord(value) && Number.isFinite(value.lower) && Number.isFinite(value.upper)
    && value.lower >= 0 && value.upper >= value.lower
}

function sourceLines(source: RecipeText): ReviewAnchor[] {
  return source.blocks.flatMap((block, index) => {
    if (block.type === 'list')
      return block.items.map((text, item) => ({ block: index, item, text }))
    if (block.type === 'image')
      return []
    return [{ block: index, text: block.text }]
  })
}

function anchorsMatch(lines: ReturnType<typeof sourceLines>, anchors: unknown): anchors is ReviewAnchor[] {
  return Array.isArray(anchors) && anchors.every(anchor => isRecord(anchor)
    && Number.isInteger(anchor.block)
    && (anchor.item === undefined || Number.isInteger(anchor.item))
    && typeof anchor.text === 'string'
    && lines.some(line => line.block === anchor.block && line.item === anchor.item && line.text === anchor.text))
}

function hasRequiredChineseReferences(references: unknown): references is InstitutionalLowGIChineseReference[] {
  if (!Array.isArray(references) || references.length !== institutionalLowGIChineseReferences.length)
    return false
  return institutionalLowGIChineseReferences.every((required) => {
    const reference = references.find(item => isRecord(item) && item.id === required.id)
    return isRecord(reference)
      && reference.publisher === required.publisher
      && reference.title === required.title
      && reference.url === required.url
      && reference.scope === required.scope
  })
}

function validDietaryEntry(entry: unknown, id: string, recipe: RecipeItem | undefined, text: RecipeText | undefined): entry is DietaryEntry {
  if (!isRecord(entry) || !recipe || !text || recipe.stuff.length >= 10)
    return false
  if (entry.recipeFingerprint !== dietaryRecipeFingerprint(recipe)
    || entry.sourceLink !== recipe.link
    || entry.sourceFingerprint !== recipeTextFingerprint(text)
    || !isValidDate(entry.reviewedAt)
    || !Array.isArray(entry.ingredients) || !entry.ingredients.length
    || new Set(entry.ingredients).size !== entry.ingredients.length
    || !Array.isArray(entry.avoidIngredients)
    || !Array.isArray(entry.unresolvedIngredients)
    || !Array.isArray(entry.foodGroups) || !entry.foodGroups.length
    || !entry.ingredients.every((name: unknown) => typeof name === 'string' && name.length > 0 && entry.avoidIngredients.includes(name))
    || !entry.unresolvedIngredients.every((name: unknown) => entry.ingredients.includes(name))) {
    return false
  }
  const lines = sourceLines(text)
  if (!anchorsMatch(lines, entry.ingredientEvidence)
    || !entry.ingredients.every(name => entry.ingredientEvidence.some((anchor: ReviewAnchor) => anchor.text.includes(name)))) {
    return false
  }
  if (!isRecord(entry.assessments) || !dietaryGoals.every(goal => isRecord(entry.assessments[goal])))
    return false
  for (const goal of dietaryGoals) {
    const assessment = entry.assessments[goal]
    if (!['pass', 'fail', 'unknown'].includes(assessment.status)
      || typeof assessment.reason !== 'string'
      || !Array.isArray(assessment.evidence) || !assessment.evidence.length
      || !anchorsMatch(lines, assessment.evidence)) {
      return false
    }
    const reasons = allowedDietaryReasons[goal]
    if (assessment.status === 'pass') {
      if (assessment.reason !== reasons.pass)
        return false
      if (goal === 'less-added-sugar'
        && (entry.unresolvedIngredients.length || entry.ingredients.some(name => addedSugarIngredients.includes(name)))) {
        return false
      }
      if (goal === 'weight-aware') {
        const oil = entry.portionOil
        const ranges = isRecord(oil) ? [oil.batchOilGrams, oil.batchOilMilliliters].filter(value => value !== undefined) : []
        const range = ranges[0]
        if (!isRecord(oil) || ranges.length !== 1 || !isValidRange(range)
          || !Number.isInteger(oil.referenceServings) || oil.referenceServings < 1 || oil.referenceServings > 20
          || !anchorsMatch(lines, oil.servingEvidence) || !anchorsMatch(lines, oil.oilEvidence)) {
          return false
        }
      }
    }
    else if (!reasons.other.includes(assessment.reason)) {
      return false
    }
  }
  // The ID is retained only when its annotation also resolves to the current catalog record.
  return String(recipe.id) === id
}

function validInstitutionalEntry(entry: unknown, id: string, recipe: RecipeItem | undefined): entry is InstitutionalLowGIEntry {
  if (!isRecord(entry) || !recipe || String(recipe.id) !== id
    || entry.recipeFingerprint !== dietaryRecipeFingerprint(recipe)
    || recipe.link !== entry.sourceURL || recipe.bv || !recipe.tags?.includes(institutionalLowGITag)
    || !/^https:\/\/glycemicindex\.com\/\d{4}\/\d{2}\/low-gi-recipes?-of-the-month(?:-\d+)?\/$/.test(String(entry.sourceURL))
    || !['Low GI Recipe of the Month', 'Low GI Recipes of the Month'].includes(String(entry.classificationTitle))
    || !isValidDate(entry.reviewedAt)
    || !Array.isArray(entry.ingredients) || !entry.ingredients.length
    || new Set(entry.ingredients).size !== entry.ingredients.length
    || !Array.isArray(entry.avoidIngredients) || !entry.avoidIngredients.length
    || !Array.isArray(entry.unresolvedIngredients)
    || !recipe.stuff.every(name => entry.avoidIngredients.includes(name))
    || ![entry.sourceRecipeTitle, entry.author, entry.requirements, entry.ingredientEvidence, entry.preparationEvidence]
      .every(value => typeof value === 'string' && value.trim().length > 0)) {
    return false
  }
  return true
}

function validLunchEntry(entry: unknown, id: string, recipe: RecipeItem | undefined, text: RecipeText | undefined): entry is LunchReviewEntry {
  if (!isRecord(entry) || !recipe || !text || String(recipe.id) !== id
    || entry.name !== recipe.name || entry.sourceLink !== recipe.link
    || entry.sourceFingerprint !== recipeTextFingerprint(text)
    || !Number.isInteger(entry.referenceServings) || entry.referenceServings < 1
    || !isRecord(entry.addedOil)
    || !['g', 'ml'].includes(String(entry.addedOil.unit))) {
    return false
  }
  const oil = entry.addedOil
  if (!isValidRange({ lower: oil.lower, upper: oil.upper }))
    return false
  const lines = sourceLines(text)
  return anchorsMatch(lines, entry.servingEvidence)
    && anchorsMatch(lines, entry.mainIngredientEvidence)
    && anchorsMatch(lines, oil.evidence)
}

async function sha256Hex(value: string) {
  if (!globalThis.crypto?.subtle)
    throw new Error('浏览器安全哈希能力不可用')
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}

async function documentVersion(data: { dataVersion: string }) {
  const { dataVersion: _version, ...content } = data
  return (await sha256Hex(JSON.stringify(content))).slice(0, 12)
}

function unavailable<T extends string>(ids: T[]): Record<T, ReviewAvailability> {
  return Object.fromEntries(ids.map(id => [id, { enabled: false, count: 0 }])) as Record<T, ReviewAvailability>
}

export async function buildReviewedRecipeCatalog(input: {
  recipes: RecipeItem[]
  texts: RecipeTextData
  amountsSource: string
  dietary: DietaryData
  institutional: InstitutionalLowGIData
  lunch: LunchReviewData
}): Promise<ReviewedRecipeCatalog> {
  const recipesByID = new Map(input.recipes.map(recipe => [String(recipe.id), recipe]))
  const goals = unavailable<DietaryGoalID>([...dietaryGoals])
  const scenes = unavailable<DiscoverySceneID>(['next-day-lunch', 'institutional-low-gi'])
  const catalog: ReviewedRecipeCatalog = {
    goals,
    scenes,
    dietaryEntries: {},
    institutionalEntries: {},
    lunchEntries: {},
    institutionalChineseReferences: [],
    messages: {},
  }
  const textSnapshotValid = input.texts.source.name === 'HowToCook'
    && input.texts.source.url === 'https://github.com/Anduin2017/HowToCook'
    && /^[a-f0-9]{40}$/.test(input.texts.source.commit)
  if (textSnapshotValid)
    catalog.textSourceCommit = input.texts.source.commit

  let dietaryAllowed = textSnapshotValid && [1, 2].includes(input.dietary.schemaVersion)
    && input.dietary.policyVersion === input.dietary.schemaVersion
    && /^[a-f0-9]{12}$/.test(input.dietary.dataVersion)
    && Array.isArray(input.dietary.enabledGoals)
    && isRecord(input.dietary.recipes)
  if (dietaryAllowed) {
    try {
      dietaryAllowed = input.dietary.dataVersion === await documentVersion(input.dietary)
        && input.dietary.amountsFingerprint === await sha256Hex(input.amountsSource)
    }
    catch {
      dietaryAllowed = false
    }
  }
  if (dietaryAllowed) {
    for (const [id, entry] of Object.entries(input.dietary.recipes)) {
      const recipe = recipesByID.get(id)
      if (validDietaryEntry(entry, id, recipe, input.texts.recipes[id]))
        catalog.dietaryEntries[id] = entry
    }
    const enabled = new Set(input.dietary.enabledGoals)
    const covered = Object.values(catalog.dietaryEntries)
      .filter(entry => dietaryGoals.some(goal => enabled.has(goal) && entry.assessments[goal].status === 'pass'))
      .length
    for (const goal of dietaryGoals) {
      const count = Object.values(catalog.dietaryEntries)
        .filter(entry => entry.assessments[goal].status === 'pass')
        .length
      goals[goal] = {
        enabled: enabled.has(goal) && covered >= 30 && count >= 10,
        count,
        reason: enabled.has(goal) ? undefined : '该目标未在审核数据中开放',
      }
    }
  }
  else {
    catalog.messages.dietary = '饮食目标审核数据缺失、版本不兼容或来源已变化，筛选已关闭。'
  }

  let institutionalAllowed = input.institutional.schemaVersion === 1
    && input.institutional.policyVersion === 1
    && input.institutional.publisher === 'GI News / University of Sydney'
    && input.institutional.authorityURL === institutionalLowGIAuthorityURL
    && input.institutional.claimKind === 'institutional-editorial'
    && hasRequiredChineseReferences(input.institutional.chineseReferences)
    && isRecord(input.institutional.recipes)
  if (institutionalAllowed) {
    try {
      institutionalAllowed = input.institutional.dataVersion === await documentVersion(input.institutional)
    }
    catch {
      institutionalAllowed = false
    }
  }
  if (institutionalAllowed) {
    const identities = new Set<string>()
    for (const [id, entry] of Object.entries(input.institutional.recipes)) {
      const identity = `${entry.sourceURL}\n${entry.sourceRecipeTitle}`
      if (identities.has(identity) || !validInstitutionalEntry(entry, id, recipesByID.get(id)))
        continue
      identities.add(identity)
      catalog.institutionalEntries[id] = entry
    }
    catalog.institutionalChineseReferences = input.institutional.chineseReferences
    const count = Object.keys(catalog.institutionalEntries).length
    scenes['institutional-low-gi'] = {
      enabled: count >= 10,
      count,
      reason: count >= 10 ? undefined : '有效机构原方不足 10 道',
    }
  }
  else {
    catalog.messages.institutional = '机构来源审核数据缺失、版本不兼容或来源已变化，筛选已关闭。'
  }

  const lunchSourceMatches = input.lunch.source.name === input.texts.source.name
    && input.lunch.source.url === input.texts.source.url
    && input.lunch.source.license === input.texts.source.license
    && input.lunch.source.commit === input.texts.source.commit
  if (textSnapshotValid && input.lunch.schemaVersion === 1 && lunchSourceMatches && input.lunch.tag === '次日便当' && isRecord(input.lunch.recipes)) {
    for (const [id, entry] of Object.entries(input.lunch.recipes)) {
      if (validLunchEntry(entry, id, recipesByID.get(id), input.texts.recipes[id]))
        catalog.lunchEntries[id] = entry
    }
    catalog.lunchMeta = {
      scope: input.lunch.scope,
      storageGuide: input.lunch.storageGuide,
      reviewedAt: input.lunch.reviewedAt,
    }
    const count = Object.keys(catalog.lunchEntries).length
    scenes['next-day-lunch'] = {
      enabled: count >= 10,
      count,
      reason: count >= 10 ? undefined : '有效便当审核记录不足 10 道',
    }
  }
  else {
    catalog.messages.lunch = '次日便当审核数据缺失、版本不兼容或来源已变化，筛选已关闭。'
  }
  return catalog
}

/** Open the source revision whose imported text was used for the review. */
export function reviewedPreparationLink(catalog: ReviewedRecipeCatalog | null, recipeID: number | undefined) {
  if (!catalog?.textSourceCommit || typeof recipeID !== 'number')
    return ''
  const entry = catalog.dietaryEntries[String(recipeID)] ?? catalog.lunchEntries[String(recipeID)]
  if (!entry)
    return ''
  const match = /^https:\/\/github\.com\/Anduin2017\/HowToCook\/blob\/[^/]+\/(.+)$/.exec(entry.sourceLink)
  return match ? `https://github.com/Anduin2017/HowToCook/blob/${catalog.textSourceCommit}/${match[1]}` : ''
}

export async function loadReviewedRecipeCatalog(recipes: RecipeItem[]): Promise<ReviewedRecipeCatalog> {
  const [
    { default: dietary },
    { default: texts },
    { default: institutional },
    { default: lunch },
    { default: amountsSource },
  ] = await Promise.all([
    import('@cook/data/recipe-dietary.json'),
    import('@cook/data/recipe-text.json'),
    import('@cook/data/recipe-institutional-low-gi.json'),
    import('../../../../specs/dietary-goals/next-day-lunch.json'),
    import('@cook/data/recipe-amounts.json?raw'),
  ])
  return buildReviewedRecipeCatalog({
    recipes,
    dietary: dietary as DietaryData,
    texts: texts as RecipeTextData,
    institutional: institutional as InstitutionalLowGIData,
    lunch: lunch as LunchReviewData,
    amountsSource,
  })
}

export function reviewedDietaryEntryAllows(
  entry: Pick<DietaryEntry, 'avoidIngredients' | 'unresolvedIngredients'>,
  avoid: string[],
  noSpicy: boolean,
) {
  const avoided = new Set(uniqueStuff([...avoid, ...(noSpicy ? ['辣椒'] : [])]))
  if (!avoided.size)
    return true
  if (entry.unresolvedIngredients.length)
    return false
  const recorded = new Set(uniqueStuff(entry.avoidIngredients))
  return [...avoided].every(name => !recorded.has(name))
}

export function reviewedInstitutionalEntryAllows(
  entry: Pick<InstitutionalLowGIEntry, 'avoidIngredients' | 'unresolvedIngredients'>,
  avoid: string[],
  noSpicy: boolean,
) {
  return reviewedDietaryEntryAllows(entry, avoid, noSpicy)
}

export function dietaryGoalOptions(catalog: ReviewedRecipeCatalog | null): ReviewedFilterOption<DietaryGoalID>[] {
  return dietaryGoals.map((id) => {
    const detail = dietaryGoalDetails[id]
    const availability = catalog?.goals[id]
    return { id, ...detail, count: availability?.count ?? 0, enabled: availability?.enabled ?? false }
  })
}

export function discoverySceneOptions(catalog: ReviewedRecipeCatalog | null): ReviewedFilterOption<DiscoverySceneID>[] {
  return (['next-day-lunch', 'institutional-low-gi'] as const).map((id) => {
    const detail = discoverySceneDetails[id]
    const availability = catalog?.scenes[id]
    return { id, ...detail, count: availability?.count ?? 0, enabled: availability?.enabled ?? false }
  })
}

export function formatQuantity(value: number) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

export function formatOilReference(
  referenceServings: number,
  range: OilRange,
  unit: 'g' | 'ml',
  perServing = false,
) {
  const lower = perServing ? range.lower / referenceServings : range.lower
  const upper = perServing ? range.upper / referenceServings : range.upper
  const quantity = lower === upper
    ? formatQuantity(lower)
    : `${formatQuantity(lower)}–${formatQuantity(upper)}`
  return `${quantity} ${unit}`
}

export function dietaryReviewReason(goal: DietaryGoalID, entry: DietaryEntry) {
  if (goal === 'weight-aware') {
    const oil = entry.portionOil
    const range = oil?.batchOilGrams ?? oil?.batchOilMilliliters
    const unit = oil?.batchOilGrams ? 'g' : 'ml'
    if (!oil || !range)
      return '份量、用油信息待核对'
    return `按原文 ${oil.referenceServings} 人份均分，每人额外烹调油 ${formatOilReference(oil.referenceServings, range, unit, true)}`
  }
  if (goal === 'less-added-sugar')
    return '已核对做法中的原料、调料和步骤，未额外添加糖。'
  const groups = entry.foodGroups.map(group => foodGroupLabels[group]).join('、')
  const missing = (Object.entries(foodGroupLabels) as [keyof typeof foodGroupLabels, string][])
    .filter(([group]) => !entry.foodGroups.includes(group))
    .map(([, label]) => label)
  const hint = missing.length
    ? `这一餐还可以搭配${missing.join('、')}。`
    : '做法包含三类食物，可结合实际份量安排这一餐。'
  return `提供${groups}。${hint}`
}
