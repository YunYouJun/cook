import type { AmountAnnotations } from './lib/recipe-amounts'
import fs from 'node:fs'
import Ajv from 'ajv'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { validateAmountAnnotations } from './lib/validate-recipe-amounts'

const read = (file: string) => JSON.parse(fs.readFileSync(`packages/data/${file}`, 'utf8'))
const data = read('recipe-amounts.json')
const ajv = new Ajv({ allErrors: true })
if (!ajv.validate<AmountAnnotations>(read('schema/recipe-amounts.schema.json'), data))
  throw new Error(ajv.errorsText(ajv.errors))
const ids = new Set(parseRecipeCsv(fs.readFileSync('packages/data/recipe.csv', 'utf8')).map(r => r.id!))
const errors = validateAmountAnnotations(data, read('recipe-text.json'), ids)
if (errors.length)
  throw new Error(errors.join('\n'))
console.log(`用量标注 schema、目录 ID 与原文锚点校验通过：${Object.keys(data.recipes).length} 道。`)
