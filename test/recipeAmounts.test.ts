import fs from 'node:fs'
import { describe, expect, it } from 'vitest'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { parsePreparationLine } from '../scripts/lib/recipe-amount-extraction'
import { isContainedIngredientMention, literal, matchesAnchor, parseFormula, parseLiteral, sourceFingerprint, sourceLines } from '../scripts/lib/recipe-amounts'
import { validateAmountAnnotations } from '../scripts/lib/validate-recipe-amounts'

const texts = JSON.parse(fs.readFileSync('packages/data/recipe-text.json', 'utf8'))
const annotations = JSON.parse(fs.readFileSync('packages/data/recipe-amounts.json', 'utf8'))
const ids = new Set(parseRecipeCsv(fs.readFileSync('packages/data/recipe.csv', 'utf8')).map(r => r.id!))

describe('source anchored recipe amounts', () => {
  it('does not diagnose sesame oil volume as a sesame mass conflict', () => {
    const text = '10g 芝麻 + 5ml 芝麻油'
    expect(isContainedIngredientMention(text, '芝麻', text.indexOf('芝麻'), ['芝麻', '芝麻油'])).toBe(false)
    expect(isContainedIngredientMention(text, '芝麻', text.lastIndexOf('芝麻'), ['芝麻', '芝麻油'])).toBe(true)
  })
  it('covers the exact offline text ID set and every annotation anchor', () => {
    expect(Object.keys(annotations.recipes).sort()).toEqual(Object.keys(texts.recipes).sort())
    expect(validateAmountAnnotations(annotations, texts, ids)).toEqual([])
    expect(sourceFingerprint(texts.recipes['250'])).toBe('12257c0baaf2700b')
    expect(sourceLines(texts.recipes['250']).length).toBeGreaterThan(texts.recipes['250'].blocks.length)
  })

  it('rejects source changes, absent IDs and corrupt or ambiguous anchors', () => {
    const changed = structuredClone(texts)
    changed.recipes['250'].blocks[0].text += '来源改动'
    expect(validateAmountAnnotations(annotations, changed, ids).some(e => e.includes('250: 来源指纹失配'))).toBe(true)
    const bad = structuredClone(annotations)
    bad.recipes['259'].usages[0].target = '不存在'
    bad.recipes['259'].quantities[0].evidence[0].text = '并非原句'
    bad.recipes['259'].quantities[1].aliases.push('土豆')
    const errors = validateAmountAnnotations(bad, texts, new Set())
    expect(errors.some(e => e.includes('标注 ID 无目录'))).toBe(true)
    const errorsWithIds = validateAmountAnnotations(bad, texts, ids)
    expect(errorsWithIds.some(e => e.includes('原文锚点失配'))).toBe(true)
    expect(errorsWithIds.some(e => e.includes('片段不唯一'))).toBe(true)
    expect(errorsWithIds.some(e => e.includes('别名同时指向'))).toBe(true)
    expect(matchesAnchor({ block: 999, text: '越界' }, texts.recipes['250'])).toBe(false)
  })

  it('parses whole quantities without guessing density, mixed units, branches or partial formulas', () => {
    expect(parseLiteral('1 茶匙')).toEqual(literal(1, '茶匙'))
    expect(parseLiteral('250g - 500g')).toEqual(literal(250, 'g', 500))
    expect(parseLiteral('1g - 2ml')).toBeUndefined()
    expect(parseLiteral('2 1/2 茶匙')).toEqual(literal(2.5, '茶匙'))
    for (const text of ['少许盐', '1g 或 2g', '1g * 份数 + 未知数量', '份数 * 一把半', '份数 * 4g - 份数 * 6g', '1g / 0人'])
      expect(parseFormula(text, new Map(), new Map()), text).toBeUndefined()
  })

  it('keeps parenthetical allocations and ratios intact instead of inventing ingredient names', () => {
    expect(parsePreparationLine('淀粉 10g（可选，其中 5g 涂青椒内壁、5g 水淀粉勾芡）')?.name).toBe('淀粉')
    expect(parsePreparationLine('淀粉 10g（可选，其中 5g 涂青椒内壁、5g 水淀粉勾芡）')?.expression).toBe('10g')
    expect(parsePreparationLine('猪肉馅 250g（肥瘦比例 3:7）')?.name).toBe('猪肉馅')
    expect(parsePreparationLine('5g 水淀粉勾芡）')).toBeUndefined()
    expect(parsePreparationLine('盐 + 鸡精 + 十三香')).toBeUndefined()
    const names = Object.values(annotations.recipes).flatMap((e: any) => e.quantities.map((q: any) => q.name))
    expect(names).not.toContain('水淀粉勾芡）')
    expect(names).not.toContain('带皮')
  })

  it('retains fixed sauce batches and optional ingredient evidence', () => {
    const sauce = annotations.recipes['401']
    expect(sauce.quantities.find((q: any) => q.name === '食用油').formula).toEqual(literal(100, 'ml'))
    expect(sauce.quantities.find((q: any) => q.name === '干面条').formula.op).toBe('multiply')
    const bamboo = annotations.recipes['812'].quantities.find((q: any) => q.name === '冬笋')
    expect(bamboo.conditional).toBe(true)
    expect(bamboo.evidence.some((e: any) => e.text.includes('冬笋（可选）'))).toBe(true)
    expect(annotations.recipes['695'].quantities.some((q: any) => q.name === '豆角')).toBe(false)
  })
})
