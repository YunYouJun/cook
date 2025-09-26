/**
 * 食物相克规则
 */
export interface IncompatibleRule {
  foodA: string
  foodB: string
  reason: string
}
