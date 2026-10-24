import Foundation

/// 由 TS 审查入口编译调用，直接运行生产 CookCore。
@main
struct AmountAudit {
    static func main() throws {
        let directory = URL(fileURLWithPath: CommandLine.arguments[1])
        let library = try RecipeTextLibrary.load(from: directory)!
        let catalog = try JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent("recipe.json"))) as! [[String: Any]]
        var output: [String: Any] = [:]
        for recipe in catalog {
            let id = recipe["id"] as! Int
            guard let text = library.text(for: id) else { continue }
            var original = text
            original.amountAnnotations = nil
            func inspect(_ source: RecipeText) -> [String: Any] {
                let presentation = RecipePresentation(text: source)
                var variants: [String: Any] = [:]
                for servings in [1, 2, 3, 20] {
                    let amounts = RecipeAmounts(rows: presentation.allQuantities, servings: servings)
                    let plan = RecipeStepUsagePlan(steps: presentation.steps, amounts: amounts, annotations: presentation.amountAnnotations)
                    variants[String(servings)] = [
                        "quantities": presentation.allQuantities.map { row -> [String: Any] in
                            let value = amounts.amount(for: row)
                            return ["name": row.name, "source": row.amount, "text": value.text,
                                    "resolved": value.isResolved, "scalable": value.adjustsWithServings,
                                    "conditional": value.isConditional,
                                    "hasKnownUnit": value.measurement != nil || row.annotation.flatMap { amounts.measurement($0.formula) } != nil,
                                    "numericMeasurement": (value.measurement ?? row.annotation.flatMap { amounts.measurement($0.formula) }).map { ["lower": $0.lower, "upper": $0.upper, "unit": $0.unit] as [String: Any] } ?? [:],
                                    "measurement": value.measurement.map { ["lower": $0.lower, "upper": $0.upper, "unit": $0.unit] as [String: Any] } ?? [:]]
                        },
                        "steps": presentation.steps.map { step -> [String: Any] in
                            let rendered = plan.instruction(for: step)!
                            return ["id": step.id, "number": step.number, "sourceBlock": step.sourceBlock, "sourceItem": step.sourceItem.map { $0 as Any } ?? NSNull(), "source": step.text, "text": rendered.text,
                                    "adjusted": rendered.hasAdjustedAmounts, "unresolved": rendered.hasUnresolvedFormula]
                        },
                        "supportsServings": amounts.supportsServings,
                        "usageMeasurements": (presentation.amountAnnotations?.usages ?? []).compactMap { use -> [String: Any]? in
                            guard use.kind == "amount", let formula = use.formula, let value = amounts.measurement(formula) else { return nil }
                            return ["ingredient": use.ingredient, "source": use.anchor.text, "conditional": use.conditional || amounts.isConditional(formula), "lower": value.lower, "upper": value.upper, "unit": value.unit]
                        },
                    ]
                }
                return ["variants": variants, "ingredients": presentation.ingredients, "annotationLoaded": presentation.amountAnnotations != nil]
            }
            output[String(id)] = ["before": inspect(original), "after": inspect(text), "fingerprint": RecipeAmountAnnotations.fingerprint(text)]
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]))
    }
}
