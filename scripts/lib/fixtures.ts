import type { RecipeItem } from '../../packages/cook/src/types'
import type { RecommendationOptions } from '../../packages/cook/src/utils/recommend'
import { createRecipeRecommender, uniqueStuff } from '../../packages/cook/src/utils/recommend'

/**
 * Web 和 iOS 共用的推荐用例：输入是筛选条件，输出是当前 TS 实现的结果。
 * iOS 的 CookCore 读同一份文件，结果必须完全一致（顺序也算）。
 */
export const recommendInputs: RecommendationOptions[] = [
  { stuff: ['番茄', '鸡蛋'], pantry: ['油', '盐'] },
  { stuff: ['西红柿', '鸡蛋'], pantry: ['油', '盐'] },
  { stuff: ['土豆'], pantry: ['油', '盐'] },
  { stuff: ['腊肠', '米'], pantry: ['油', '盐'], tool: '电饭煲' },
  { stuff: ['鸡肉', '洋葱', '菌菇'], pantry: ['油', '盐', '生抽', '蒜'] },
  { stuff: ['牛肉', '番茄', '米'], pantry: [] },
  { stuff: ['番茄', '鸡蛋'], pantry: ['油', '盐'], mode: 'strict' },
  { stuff: ['番茄', '鸡蛋', '米'], pantry: ['油', '盐'], mode: 'survival' },
  { stuff: ['鸡蛋'], pantry: ['油', '盐'], keyword: '蛋' },
  { stuff: ['猪肉', '白菜', '豆腐'], pantry: ['油', '盐'], avoid: ['猪肉'] },
  { stuff: ['猪肉', '白菜', '豆腐', '菌菇'], pantry: ['油', '盐'], avoid: ['豆腐'] },
  { stuff: ['鸡肉', '土豆'], pantry: ['油', '盐'], methodGroups: ['炖煮', '烤'] },
  { stuff: ['鸡肉', '土豆'], pantry: ['油', '盐'], noSpicy: true },
  { stuff: ['鱼'], pantry: ['油', '盐', '葱', '姜'], difficulty: ['简单'] },
  { stuff: ['鸡蛋', '面食'], pantry: ['油', '盐'], tool: '一口大锅', mode: 'strict' },
  { stuff: ['火龙果'] },
  { stuff: [] },
]

/** 别名和分隔符处理 */
export const normalizeInputs: string[][] = [
  ['西红柿', '鸡蛋'],
  ['土豆丝、马铃薯', ' 洋芋 '],
  ['鸡翅,鸡腿；排骨'],
  ['虾仁\n鲈鱼', ''],
]

export function buildFixtures(recipes: RecipeItem[]) {
  const recommend = createRecipeRecommender(recipes)
  return {
    description: '由 pnpm fixtures 生成，勿手改。Web（test/fixtures.test.ts）和 iOS（CookCore）都要和它一致。',
    recipeCount: recipes.length,
    normalize: normalizeInputs.map(input => ({ input, output: uniqueStuff(input) })),
    recommend: recommendInputs.map(input => ({
      input,
      output: recommend(input).map(match => ({
        id: match.recipe.id,
        matched: match.matchedStuff,
        pantry: match.pantryStuff,
        missing: match.missingStuff,
        optional: match.optionalStuff,
      })),
    })),
  }
}
