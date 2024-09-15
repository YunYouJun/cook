import type { Table } from 'dexie'
import Dexie from 'dexie'

import type { RecipeItem } from '~/types'

export interface DbRecipeItem extends RecipeItem {
  id?: number
}

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

export async function initDb() {
  const { default: recipeData } = await import('../data/recipe.json')

  return db.recipes.bulkPut(
    (recipeData as RecipeItem[]).map((item, i) => ({
      id: i,
      ...item,
    })),
  )
}
