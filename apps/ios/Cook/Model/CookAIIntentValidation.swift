import CookCore
import Foundation

enum CookAIIntentValidationFailure: Error { case conflictsWithLocal }

/// Both remote paths preserve the same locally recognized limits before editing or application.
enum CookAIIntentValidation {
    static func preserveLocalConditions(_ remote: SearchIntent, text: String, data: CookData,
                                        availableGoals: [DietaryGoal]) throws -> SearchIntent {
        let validator = SearchIntentValidator(data: data, availableGoals: availableGoals)
        var result = try validator.validate(remote, expectedSource: .deepseek)
        let local = LocalSearchIntentParser(data: data, availableGoals: availableGoals).parse(text)
        if let goal = local.goal {
            guard result.goal == nil || result.goal == goal else { throw CookAIIntentValidationFailure.conflictsWithLocal }
            result.goal = goal
        }
        if let tool = local.query.tool {
            guard result.query.tool == nil || result.query.tool == tool else { throw CookAIIntentValidationFailure.conflictsWithLocal }
            result.query.tool = tool
        }
        result.query.stuff = uniqueStrings(local.query.stuff + result.query.stuff)
        result.query.avoid = uniqueStrings(local.query.avoid + result.query.avoid)
        result.query.noSpicy = local.query.noSpicy || result.query.noSpicy
        // These arrays are OR filters; adding model alternatives would broaden an explicit choice.
        if !local.query.methodGroups.isEmpty { result.query.methodGroups = local.query.methodGroups }
        if !local.query.difficulty.isEmpty { result.query.difficulty = local.query.difficulty }
        result.unresolved = uniqueStrings(local.unresolved + result.unresolved)
        if result.goal != nil, local.goal == nil,
           (unsupportedHealthRequests.contains(where: text.contains)
            || DietaryGoal.allCases.contains(where: { text.contains($0.title) })) {
            throw CookAIIntentValidationFailure.conflictsWithLocal
        }
        return try validator.validate(result, expectedSource: .deepseek)
    }

    private static let unsupportedHealthRequests = ["控制体重", "减肥", "减脂", "生酮", "痘痘", "祛痘", "孕期", "孕妇", "糖尿病", "控糖", "血糖", "高血压", "降糖"]
}
