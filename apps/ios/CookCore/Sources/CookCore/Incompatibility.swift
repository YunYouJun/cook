import Foundation

/// 已选食材里同时出现的相克组合；少于两样食材时不检查。
/// 移植自 packages/cook/src/utils/incompatible.ts。
public func findIncompatibleRules(_ ingredients: [String], rules: [IncompatibleRule]) -> [IncompatibleRule] {
    if ingredients.count < 2 {
        return []
    }
    let selected = Set(ingredients)
    return rules.filter { selected.contains($0.foodA) && selected.contains($0.foodB) }
}
