import type { RecipeItem, Recipes } from '../app/types'
// convert csv to json
import fs from 'node:fs'
import consola from 'consola'

import { config } from './config'

// 定义食物相克规则的类型
interface IncompatibleRule {
  foodA: string
  foodB: string  
  reason: string
}

function run() {
  const csvData = fs.readFileSync(config.recipeCsvFile, 'utf-8')
  const lines = csvData.split(/\r?\n/)

  const headers = 'name,stuff,bv,difficulty,tags,methods,tools,'
  if (lines.length < 2) {
    throw new Error('No data in csv file')
  }

  if (lines[0]?.trim() !== headers) {
    consola.warn(`Headers Changed: ${lines[0]}`)
    return
  }

  const recipeJson: Recipes = []
  const sep = '、'

  lines.slice(1).forEach((line) => {
    if (line) {
      const attrs = line.split(',')
      if (attrs.length < 7) {
        consola.warn(`Invalid line: ${line}`)
        return
      }
      const stuff = attrs[1]?.trim().split(sep) || []
      recipeJson.push({
        name: attrs[0]?.trim() || '',
        stuff,
        // link: attrs[2].trim(),
        // bv id
        bv: attrs[2]?.trim().replace('https://www.bilibili.com/video/', ''),
        difficulty: attrs[3] && attrs[3].trim() as RecipeItem['difficulty'],
        tags: attrs[4] ? attrs[4].trim().split(sep) : [],
        methods: attrs[5] ? (attrs[5].trim().split(sep)) as RecipeItem['methods'] : [],
        tools: attrs[6] ? attrs[6].trim().split(sep) : [],
      })
    }
  })

  fs.writeFileSync(config.recipeJsonFile, JSON.stringify(recipeJson))
  consola.success(`Generate file: ${config.recipeJsonFile}`)
}

/**
 * 转换食物相克数据
 */
function convertIncompatibleFoods() {
  consola.info('---')
  consola.info('Convert Incompatible Foods Data...')
  
  try {
    const csvData = fs.readFileSync(config.incompatibleFoodsCsvFile, 'utf-8')
    const lines = csvData.split(/\r?\n/)
    
    const headers = 'foodA,foodB,reason'
    if (lines.length < 2) {
      throw new Error('No data in incompatible foods csv file')
    }

    if (lines[0]?.trim() !== headers) {
      consola.warn(`Headers Changed: ${lines[0]}`)
      return
    }

    const incompatibleRules: IncompatibleRule[] = []

    lines.slice(1).forEach((line) => {
      if (line.trim()) {
        const attrs = line.split(',')
        if (attrs.length < 3) {
          consola.warn(`Invalid line: ${line}`)
          return
        }
        incompatibleRules.push({
          foodA: attrs[0]?.trim() || '',
          foodB: attrs[1]?.trim() || '',
          reason: attrs[2]?.trim() || '',
        })
      }
    })

    fs.writeFileSync(config.incompatibleFoodsJsonFile, JSON.stringify(incompatibleRules, null, 2))
    consola.success(`Generate file: ${config.incompatibleFoodsJsonFile}`)
  } catch (error) {
    consola.error('Failed to convert incompatible foods data:', error)
  }
}

function main() {
  run()
  convertIncompatibleFoods()
}

main()
