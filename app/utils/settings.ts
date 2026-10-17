export type RecipeView = 'classic' | 'recommendation'

export interface UserSettings {
  /**
   * 保留本地数据
   */
  keepLocalData: boolean
  /** 上次选择的做菜视图，独立于食材数据的保存开关。 */
  recipeView: RecipeView
}

export const defaultSettings: UserSettings = {
  keepLocalData: true,
  recipeView: 'classic',
}

export function resolveRecipeView(value: unknown): RecipeView {
  return value === 'recommendation' ? 'recommendation' : 'classic'
}
