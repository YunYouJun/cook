import type { DietaryData } from './lib/dietary'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import Ajv from 'ajv'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { dietaryGoals, validateDietaryData } from './lib/dietary'

const read = (file: string) => JSON.parse(fs.readFileSync(`packages/data/${file}`, 'utf8'))
const data = read('recipe-dietary.json')
const ajv = new Ajv({ allErrors: true })
if (!ajv.validate<DietaryData>(read('schema/recipe-dietary.schema.json'), data))
  throw new Error(ajv.errorsText(ajv.errors))
const recipes = parseRecipeCsv(fs.readFileSync('packages/data/recipe.csv', 'utf8'))
const amountsFingerprint = createHash('sha256').update(fs.readFileSync('packages/data/recipe-amounts.json')).digest('hex')
const errors = validateDietaryData(data, recipes, read('recipe-text.json').recipes, amountsFingerprint)
if (errors.length)
  throw new Error(errors.join('\n'))
console.log(`饮食标注、目录、来源指纹及锚点校验通过：${Object.keys(data.recipes).length} 道；版本 ${data.dataVersion}`)
for (const goal of dietaryGoals) {
  const counts = Object.fromEntries(['pass', 'fail', 'unknown'].map(status => [status, Object.values(data.recipes).filter(entry => entry.assessments[goal].status === status).length]))
  console.log(`${goal} ${data.enabledGoals.includes(goal) ? '已开放' : '未开放'} ${JSON.stringify(counts)}`)
}
