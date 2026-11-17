import type { RecipeTextData } from '../packages/cook/src/types/recipe-text'
import type { DietaryData } from '../scripts/lib/dietary'
import type { InstitutionalLowGIData } from '../scripts/lib/institutional-low-gi'
import type { AmountAnnotations, AmountEntry, AmountFormula } from '../scripts/lib/recipe-amounts'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { describe, expect, it } from 'vitest'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { dietaryRecipeFingerprint, validateDietaryData } from '../scripts/lib/dietary'
import { validateInstitutionalLowGIData } from '../scripts/lib/institutional-low-gi'
import { parsePreparationLine } from '../scripts/lib/recipe-amount-extraction'
import { matchesAnchor, parseFormula, sourceFingerprint, sourceLines } from '../scripts/lib/recipe-amounts'
import { validateAmountAnnotations } from '../scripts/lib/validate-recipe-amounts'

interface EvaluatedValue {
  lower: number
  upper: number
  unit: string | null
}

function evalAmountFormula(
  formula: AmountFormula,
  servings: number,
  entry?: AmountEntry,
  refs?: Map<string, EvaluatedValue>,
): EvaluatedValue | undefined {
  switch (formula.op) {
    case 'literal':
      return { lower: formula.lower, upper: formula.upper ?? formula.lower, unit: formula.unit }
    case 'scalar':
      return { lower: formula.lower, upper: formula.lower, unit: null }
    case 'servings':
      return { lower: servings, upper: servings, unit: null }
    case 'reference': {
      const resolved = refs?.get(formula.name)
      if (resolved)
        return resolved
      const ref = entry?.quantities.find(q => q.name === formula.name)
      return ref ? evalAmountFormula(ref.formula, servings, entry, refs) : undefined
    }
    case 'count': {
      const ref = refs?.get(formula.name)
      if (!ref || ref.unit !== formula.unit)
        return undefined
      return { lower: ref.lower, upper: ref.upper, unit: null }
    }
    case 'ceil': {
      const inner = evalAmountFormula(formula.args[0]!, servings, entry, refs)
      return inner ? { lower: Math.ceil(inner.lower), upper: Math.ceil(inner.upper), unit: inner.unit } : undefined
    }
    case 'floor': {
      const inner = evalAmountFormula(formula.args[0]!, servings, entry, refs)
      return inner ? { lower: Math.floor(inner.lower), upper: Math.floor(inner.upper), unit: inner.unit } : undefined
    }
    case 'multiply': {
      const left = evalAmountFormula(formula.args[0]!, servings, entry, refs)
      const right = evalAmountFormula(formula.args[1]!, servings, entry, refs)
      if (!left || !right)
        return undefined
      if (left.unit && right.unit && left.unit !== right.unit)
        return undefined
      const unit = left.unit ?? right.unit
      return { lower: left.lower * right.lower, upper: left.upper * right.upper, unit }
    }
    case 'divide': {
      const left = evalAmountFormula(formula.args[0]!, servings, entry, refs)
      const right = evalAmountFormula(formula.args[1]!, servings, entry, refs)
      if (!left || !right || right.unit !== null || right.lower <= 0 || right.upper <= 0)
        return undefined
      return { lower: left.lower / right.upper, upper: left.upper / right.lower, unit: left.unit }
    }
    case 'add': {
      const left = evalAmountFormula(formula.args[0]!, servings, entry, refs)
      const right = evalAmountFormula(formula.args[1]!, servings, entry, refs)
      if (!left || !right || !left.unit || left.unit !== right.unit)
        return undefined
      return { lower: left.lower + right.lower, upper: left.upper + right.upper, unit: left.unit }
    }
  }
}

function formatNum(value: number): string {
  const rounded = Math.round(value * 100) / 100
  return Number.isInteger(rounded) ? String(rounded) : String(rounded)
}

function formatMeasured(value: EvaluatedValue): string {
  const range = Math.abs(value.upper - value.lower) < 1e-6
    ? formatNum(value.lower)
    : `${formatNum(value.lower)}–${formatNum(value.upper)}`
  return value.unit ? `${range} ${value.unit}` : range
}

function parseInstructionExample(expr: string): AmountFormula | undefined {
  const clean = expr.replace(/^（([^（）]+)）([a-z\u4E00-\u9FFF]+)$/i, '$1$2')
    .replace(/^（([^（）]+)）$/, '$1')
  return parseFormula(clean, new Map(), new Map())
}

const dataDir = resolve(process.cwd(), 'packages/data')
const readData = <T = unknown>(file: string): T => JSON.parse(readFileSync(resolve(dataDir, file), 'utf8')) as T
const readFixture = <T = unknown>(file: string): T => JSON.parse(readFileSync(resolve(dataDir, 'fixtures', file), 'utf8')) as T

const recipes = parseRecipeCsv(readFileSync(resolve(dataDir, 'recipe.csv'), 'utf8'))
const texts = readData<RecipeTextData>('recipe-text.json')
const dietary = readData<DietaryData>('recipe-dietary.json')
const lowGi = readData<InstitutionalLowGIData>('recipe-institutional-low-gi.json')

describe('shared cross-platform core contract fixtures', () => {
  it('matches recipe-text-blocks source fingerprint and bundled 250 metadata', () => {
    const fixture = readFixture<any>('recipe-text-blocks.json')
    expect(sourceFingerprint(texts.recipes[String(fixture.bundledRecipe250.id)]!)).toBe(fixture.bundledRecipe250.expectedSourceFingerprint)
    expect(texts.recipes['250']!.blocks.length).toBeGreaterThan(0)
  })

  it('validates recipe-amounts annotations, scaling evaluations, anchors, and expression rejections', () => {
    const fixture = readFixture<any>('recipe-amounts-scaling.json')
    const annotations = readData<AmountAnnotations>('recipe-amounts.json')
    const recipeIds = new Set(recipes.map(r => r.id!))

    // Web/TS 侧同时核对构建期标注指纹/锚点、AST 缩放求值结果（250 / 步骤示例 / 分配断言）与非法式拒绝
    expect(validateAmountAnnotations(annotations, texts, recipeIds)).toEqual([])
    const tomato250 = annotations.recipes[String(fixture.bundledTomato250.id)]!
    expect(tomato250).toBeDefined()

    const tomato250Text = texts.recipes[String(fixture.bundledTomato250.id)]!
    const tomato250Lines = new Map(
      sourceLines(tomato250Text)
        .filter(line => line.section.includes('计算'))
        .map(line => parsePreparationLine(line.text))
        .filter((item): item is NonNullable<typeof item> => Boolean(item) && item.expression.length > 0)
        .map(item => [item.name, item] as const),
    )
    const tomatoNames = new Map([['鸡蛋', '鸡蛋']])
    const tomatoUnits = new Map([['鸡蛋', '个']])
    const eggFormula = parseFormula(tomato250Lines.get('鸡蛋')!.expression, tomatoNames, tomatoUnits)!
    const oilFormula = parseFormula(tomato250Lines.get('食用油')!.expression, tomatoNames, tomatoUnits)!
    expect(eggFormula).toBeDefined()
    expect(oilFormula).toBeDefined()
    for (const sc of fixture.bundledTomato250.servingsCases) {
      const eggVal = evalAmountFormula(eggFormula, sc.servings, tomato250)!
      expect(formatMeasured(eggVal)).toBe(sc.eggs)
      const oilVal = evalAmountFormula(oilFormula, sc.servings, tomato250, new Map([['鸡蛋', eggVal]]))!
      expect(formatMeasured(oilVal)).toBe(sc.oil)
    }
    const saltFormula = parseFormula(tomato250Lines.get('盐')!.expression, new Map(), new Map())!
    const sugarFormula = parseFormula(tomato250Lines.get('糖')!.expression, new Map(), new Map())!
    const scallionFormula = parseFormula(tomato250Lines.get('葱花')!.expression, new Map(), new Map())!
    expect(formatMeasured(evalAmountFormula(saltFormula, 3, tomato250)!)).toBe(fixture.bundledTomato250.servings3Details['盐'])
    expect(formatMeasured(evalAmountFormula(sugarFormula, 3, tomato250)!)).toBe(fixture.bundledTomato250.servings3Details['糖（可选）'])
    expect(formatMeasured(evalAmountFormula(scallionFormula, 3, tomato250)!)).toBe(fixture.bundledTomato250.servings3Details['葱花（可选）'])

    for (const ex of fixture.bundledInstructionExamples) {
      const parsed = parseInstructionExample(ex.expression)
      expect(parsed, ex.expression).toBeDefined()
      const scaled = formatMeasured(evalAmountFormula(parsed!, ex.servings)!)
      expect(scaled, ex.expression).toBe(ex.expected)
    }

    for (const check of fixture.bundledAllocationChecks) {
      const idKey = String(check.id)
      const source = texts.recipes[idKey]
      const entry = annotations.recipes[idKey]
      expect(source).toBeDefined()
      expect(entry).toBeDefined()
      const matchedUsages = entry!.usages.filter(
        u => u.anchor.block === check.block && (u.anchor.item ?? null) === (check.item ?? null),
      )
      if (!check.expectUnresolved) {
        expect(matchedUsages.length).toBeGreaterThan(0)
        for (const u of matchedUsages) {
          expect(matchesAnchor(u.anchor, source!)).toBe(true)
        }
      }
      for (const u of matchedUsages) {
        if (u.kind === 'amount' && u.formula && u.display !== false) {
          const val = evalAmountFormula(u.formula, check.servings, entry)
          expect(val).toBeDefined()
          const formatted = formatMeasured(val!)
          const needsSnippet = (check.contains as string[]).some(snippet => snippet.includes(u.ingredient))
          if (needsSnippet) {
            expect((check.contains as string[]).some(s => s.includes(formatted)), `Expected ${formatted} inside ${JSON.stringify(check.contains)}`).toBe(true)
          }
        }
        if (u.kind === 'remaining' && !check.expectUnresolved) {
          const totalQ = entry!.quantities.find(q => q.name === u.ingredient)
          if (totalQ) {
            const totalVal = evalAmountFormula(totalQ.formula, check.servings, entry)!
            const priorUses = entry!.usages
              .filter(p => p.ingredient === u.ingredient && p.kind === 'amount' && p.formula)
              .map(p => evalAmountFormula(p.formula!, check.servings, entry)!)
            if (priorUses.length > 0 && priorUses.every(p => p.unit === totalVal.unit)) {
              const remLower = totalVal.lower - priorUses.reduce((acc, p) => acc + p.upper, 0)
              const remUpper = totalVal.upper - priorUses.reduce((acc, p) => acc + p.lower, 0)
              const remFormatted = formatMeasured({ lower: remLower, upper: remUpper, unit: totalVal.unit })
              expect((check.contains as string[]).some(s => s.includes(remFormatted)), `Expected remaining ${remFormatted} in ${JSON.stringify(check.contains)}`).toBe(true)
            }
          }
        }
      }
    }

    // build-time `parseFormula` 支持显式标注中的 `+` 与 `向上/下取整`，但必须拒绝未知引用、非法单位、减法、负数与嵌套括号
    const emptyMap = new Map<string, string>()
    const buildTimeSupportedInlineUnsupported = new Set([
      '1g * 份数 + 2g',
      '`份数 * 2g` + 1g',
      '份数 * 1.5个，向下取整',
      '`份数 * 1.5个`，向上取整',
    ])
    for (const expr of fixture.unsupportedInstructionExpressions as string[]) {
      const parsed = parseFormula(expr, emptyMap, emptyMap)
      if (buildTimeSupportedInlineUnsupported.has(expr)) {
        expect(parsed).toBeDefined()
      }
      else {
        expect(parsed).toBeUndefined()
      }
    }
  })

  it('matches dietary-contracts fingerprints, sha256 vectors and catalog summaries', () => {
    const fixture = readFixture<any>('dietary-contracts.json')
    for (const vec of fixture.sha256Vectors) {
      const digest = createHash('sha256').update(vec.input, 'utf8').digest('hex')
      expect(digest).toBe(vec.sha256)
    }

    for (const fp of fixture.recipeFingerprintCases) {
      const recipe = recipes.find(r => r.id === fp.id)!
      expect(dietaryRecipeFingerprint(recipe)).toBe(fp.expectedFingerprint)
      expect(dietaryRecipeFingerprint({ ...recipe, score: 0 })).toBe(fp.expectedFingerprint)
    }

    const amountsDigest = createHash('sha256').update(readFileSync(resolve(dataDir, 'recipe-amounts.json'))).digest('hex')
    expect(validateDietaryData(dietary, recipes, texts.recipes, amountsDigest)).toEqual([])
    expect(Object.keys(dietary.recipes)).toHaveLength(fixture.bundledDietarySummary.entryCount)
    expect(dietary.enabledGoals).toEqual(fixture.bundledDietarySummary.availableGoals)
    expect(Object.values(dietary.recipes).filter(e => e.assessments['less-added-sugar'].status === 'pass')).toHaveLength(fixture.bundledDietarySummary.lessAddedSugarCount)
    expect(Object.values(dietary.recipes).filter(e => e.assessments['weight-aware'].status === 'pass')).toHaveLength(fixture.bundledDietarySummary.weightAwareCount)

    expect(validateInstitutionalLowGIData(lowGi, recipes)).toEqual([])
    expect(Object.keys(lowGi.recipes).map(Number).sort((a, b) => a - b)).toEqual(fixture.bundledInstitutionalLowGI.expectedIds)
  })
})
