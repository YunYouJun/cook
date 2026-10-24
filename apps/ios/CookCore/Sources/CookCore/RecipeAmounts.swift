import Foundation

/// 按原文明确写出的份数公式计算，不把固定用量擅自视为「每份」。
/// 支持乘除、范围、分数、引用其他原料数量和向上取整；未知表达式保留原文。
public struct RecipeAmounts: Sendable {
    public struct Amount: Equatable, Sendable {
        public let text: String
        public let note: String?
        public let adjustsWithServings: Bool
        public let isResolved: Bool
        let measurement: RecipeMeasurement?
        let isConditional: Bool

        init(text: String, note: String?, adjustsWithServings: Bool, isResolved: Bool, measurement: RecipeMeasurement? = nil, isConditional: Bool = false) {
            self.text = text
            self.note = note
            self.adjustsWithServings = adjustsWithServings
            self.isResolved = isResolved
            self.measurement = measurement
            self.isConditional = isConditional
        }
    }

    public static let servingRange = 1 ... 20
    public let servings: Int
    private var values: [RecipePresentation.Quantity: Amount] = [:]
    private let rows: [RecipePresentation.Quantity]

    public init(rows: [RecipePresentation.Quantity], servings: Int) {
        self.rows = rows
        self.servings = min(max(servings, Self.servingRange.lowerBound), Self.servingRange.upperBound)
        let resolver = Resolver(rows: rows, servings: self.servings)
        for row in rows {
            if let result = resolver.resolve(row) {
                values[row] = Amount(text: result.display, note: result.conditional ? "依赖可选或条件原料，需确认分支" : result.note,
                                     adjustsWithServings: result.dependsOnServings, isResolved: true,
                                     measurement: result.conditional ? nil : RecipeMeasurement(lower: result.lower, upper: result.upper, unit: result.unit), isConditional: result.conditional)
            } else {
                values[row] = Amount(text: row.amount, note: "需按原文确认", adjustsWithServings: false, isResolved: false)
            }
        }
    }

    public var supportsServings: Bool { values.values.contains { $0.adjustsWithServings } }

    /// 步骤按自身公式换算，不用整道菜的总量覆盖分次投料。
    public func instruction(_ text: String) -> RecipeInstructionAmounts {
        let resolver = Resolver(rows: rows, servings: servings)
        return RecipeInstructionAmounts(text: text) { expression in
            guard let row = RecipePresentation.Quantity("步骤用量 = \(expression)"),
                  let result = resolver.resolve(row), result.dependsOnServings, !result.unit.isEmpty else { return nil }
            let rounding = result.note == "已向上取整" ? "（已向上取整）" : ""
            return result.display + rounding
        }
    }

    public func amount(for row: RecipePresentation.Quantity) -> Amount {
        values[row] ?? amount(for: row.name) ?? Amount(text: row.amount, note: "需按原文确认", adjustsWithServings: false, isResolved: false)
    }

    public func amount(for ingredient: String) -> Amount? {
        let matches = rows.filter { Self.key($0.name) == Self.key(ingredient) || ($0.annotation?.aliases.contains(ingredient) == true) }
        guard matches.count == 1, let row = matches.first else { return nil }
        return values[row]
    }

    var annotationRows: [RecipePresentation.Quantity] { rows.filter { $0.annotation != nil } }

    func measurement(_ formula: RecipeAmountAnnotations.Formula) -> RecipeMeasurement? {
        guard let value = Resolver(rows: rows, servings: servings).formula(formula) else { return nil }
        return RecipeMeasurement(lower: value.lower, upper: value.upper, unit: value.unit)
    }

    func isConditional(_ formula: RecipeAmountAnnotations.Formula) -> Bool {
        Resolver(rows: rows, servings: servings).formula(formula)?.conditional ?? true
    }

    var ingredientNames: [String] { Array(Set(rows.map { Self.key($0.name) })).sorted() }

    static func literalMeasurement(_ text: String) -> RecipeMeasurement? {
        let plain = text.replacingOccurrences(of: "`", with: "").replacingOccurrences(of: "**", with: "")
            .replacingOccurrences(of: "（已向上取整）", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard let value = Resolver.literal(plain), !value.unit.isEmpty else { return nil }
        return RecipeMeasurement(lower: value.lower, upper: value.upper, unit: value.unit)
    }

    static func key(_ name: String) -> String {
        var name = RecipePresentation.ingredientName(name).replacingOccurrences(of: "`", with: "")
        for suffix in ["的数量", "的用量", "数量", "用量", "量"] where name.hasSuffix(suffix) {
            name = String(name.dropLast(suffix.count))
            break
        }
        return name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private struct Value {
        var lower: Double
        var upper: Double
        var unit: String = ""
        var dependsOnServings = false
        var note: String?
        var annotations: [String] = []
        var conditional = false

        var isValid: Bool { lower.isFinite && upper.isFinite && lower >= 0 && upper >= lower }
        var display: String {
            let number = lower == upper ? format(lower) : "\(format(lower))–\(format(upper))"
            let quantity = unit.isEmpty ? number : "\(number) \(unit)"
            return quantity + annotations.map { "（\($0)）" }.joined()
        }

        func multiplied(by other: Value) -> Value? {
            guard unit.isEmpty || other.unit.isEmpty else { return nil }
            let result = Value(lower: lower * other.lower, upper: upper * other.upper,
                               unit: unit.isEmpty ? other.unit : unit,
                               dependsOnServings: dependsOnServings || other.dependsOnServings)
            var conditionalResult = result
            conditionalResult.conditional = conditional || other.conditional
            return conditionalResult.isValid ? conditionalResult : nil
        }

        func divided(by lowerDivisor: Double, _ upperDivisor: Double) -> Value? {
            guard lowerDivisor > 0, upperDivisor >= lowerDivisor else { return nil }
            var result = self
            result.lower /= upperDivisor
            result.upper /= lowerDivisor
            return result.isValid ? result : nil
        }
    }

    private final class Resolver {
        let rows: [RecipePresentation.Quantity]
        let servings: Int
        var cache: [RecipePresentation.Quantity: Value] = [:]
        var visiting: Set<RecipePresentation.Quantity> = []

        init(rows: [RecipePresentation.Quantity], servings: Int) {
            self.rows = rows
            self.servings = servings
        }

        func resolve(_ row: RecipePresentation.Quantity) -> Value? {
            if let value = cache[row] { return value }
            guard visiting.count < 32, visiting.insert(row).inserted else { return nil }
            defer { visiting.remove(row) }
            if let annotation = row.annotation {
                guard var result = formula(annotation.formula), result.isValid, !result.unit.isEmpty else { return nil }
                result.conditional = result.conditional || annotation.conditional
                if let note = annotation.note { result.annotations.append((result.dependsOnServings ? "原文基准说明：" : "原文说明：") + note) }
                if result.conditional { result.annotations.append("可选或条件用量，采用该分支时适用") }
                if result.note == nil { result.note = result.dependsOnServings ? "按原文份数依据换算" : "原文固定用量" }
                cache[row] = result
                return result
            }
            var expression = row.amount.replacingOccurrences(of: "`", with: "")
            let notes = matches(#"[（(]([^（）()]*)[）)]"#, in: expression).map { $0[1] }
            // 只剥离说明性括号；含运算的括号不能被当作注释丢掉。
            guard !notes.contains(where: { $0.contains("份数") || $0.rangeOfCharacter(from: CharacterSet(charactersIn: "+*×/=^")) != nil }) else { return nil }
            expression = expression.replacingOccurrences(of: #"[（(][^（）()]*[）)]"#, with: "", options: .regularExpression)
            let roundUp = expression.contains("向上取整") || notes.contains("向上取整")
            expression = expression.replacingOccurrences(of: "向上取整", with: "")
                .trimmingCharacters(in: CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: "，,。")))
            let terms = expression.components(separatedBy: CharacterSet(charactersIn: "*×"))
            guard !terms.isEmpty, terms.count <= 12 else { return nil }
            var result = Value(lower: 1, upper: 1)
            var literalBase: Value?
            let hasLiteralUnit = terms.contains { Self.literal($0.trimmingCharacters(in: .whitespaces))?.unit.isEmpty == false }
            for term in terms {
                let token = term.trimmingCharacters(in: .whitespaces)
                if let literal = Self.literal(token), !literal.unit.isEmpty { literalBase = literal }
                guard let factor = factor(token, scalarReference: hasLiteralUnit), let product = result.multiplied(by: factor) else { return nil }
                result = product
            }
            if roundUp {
                // 消除浮点乘除带来的整数边界误差，再按原文要求对总量取整。
                result.lower = max(0, ceil(result.lower - 1e-10))
                result.upper = max(0, ceil(result.upper - 1e-10))
                result.note = "已向上取整"
            }
            for note in notes where note != "向上取整" {
                let amountText = note.hasPrefix("约") ? String(note.dropFirst()) : note
                if let weight = Self.literal(amountText), !weight.unit.isEmpty,
                   let base = literalBase, base.lower > 0, base.lower == base.upper,
                   base.unit == result.unit {
                    var scaled = weight
                    scaled.lower *= result.lower / base.lower
                    scaled.upper *= result.upper / base.upper
                    guard scaled.isValid else { return nil }
                    result.annotations.append((note.hasPrefix("约") ? "约 " : "") + scaled.display)
                } else {
                    // 「每个约 150g」「无论多少人都放这些」等限定语不能删掉。
                    result.annotations.append(note)
                }
            }
            if !result.dependsOnServings { result.note = "原文固定用量" }
            cache[row] = result
            return result
        }

        func formula(_ expression: RecipeAmountAnnotations.Formula) -> Value? {
            guard expression.isValid else { return nil }
            switch expression.op {
            case "literal", "scalar":
                let lower = expression.lower!
                return Value(lower: lower, upper: expression.upper ?? lower, unit: expression.unit ?? "")
            case "servings": return Value(lower: Double(servings), upper: Double(servings), dependsOnServings: true)
            case "reference", "count":
                let matches = rows.filter { RecipeAmounts.key($0.name) == RecipeAmounts.key(expression.name!) }
                guard matches.count == 1, var result = resolve(matches[0]) else { return nil }
                if expression.op == "count" {
                    guard result.unit == expression.unit else { return nil }
                    result.unit = ""
                }
                result.annotations = []
                result.note = nil
                return result
            case "ceil", "floor":
                guard var result = formula(expression.args![0]) else { return nil }
                if expression.op == "ceil" {
                    result.lower = max(0, ceil(result.lower - 1e-10)); result.upper = max(0, ceil(result.upper - 1e-10))
                    result.note = "已向上取整"
                } else {
                    result.lower = floor(result.lower + 1e-10); result.upper = floor(result.upper + 1e-10)
                    result.note = "已向下取整"
                }
                return result.isValid ? result : nil
            default:
                guard let left = formula(expression.args![0]), let right = formula(expression.args![1]) else { return nil }
                switch expression.op {
                case "multiply": return left.multiplied(by: right)
                case "divide":
                    guard right.unit.isEmpty, var result = left.divided(by: right.lower, right.upper) else { return nil }
                    result.dependsOnServings = left.dependsOnServings || right.dependsOnServings
                    result.conditional = left.conditional || right.conditional
                    return result
                case "add":
                    guard let leftMeasure = RecipeMeasurement(lower: left.lower, upper: left.upper, unit: left.unit),
                          let rightMeasure = RecipeMeasurement(lower: right.lower, upper: right.upper, unit: right.unit),
                          let sum = leftMeasure.adding(rightMeasure) else { return nil }
                    return Value(lower: sum.lower, upper: sum.upper, unit: sum.unit, dependsOnServings: left.dependsOnServings || right.dependsOnServings, conditional: left.conditional || right.conditional)
                default: return nil
                }
            }
        }

        private func factor(_ token: String, scalarReference: Bool) -> Value? {
            if token == "份数" { return Value(lower: Double(servings), upper: Double(servings), dependsOnServings: true) }
            if let perServing = match(#"^(.+)/([0-9]+|[一二三四五六七八九十两])(人|份)$"#, in: token),
               let value = Self.literal(perServing[1]), let people = Self.number(perServing[2]), people > 0,
               var result = value.divided(by: people, people) {
                result.lower *= Double(servings)
                result.upper *= Double(servings)
                result.dependsOnServings = true
                return result.isValid ? result : nil
            }
            if let literal = Self.literal(token) { return literal }
            let parts = token.components(separatedBy: "/").map { $0.trimmingCharacters(in: .whitespaces) }
            guard parts.count <= 2 else { return nil }
            let matches = rows.filter { RecipeAmounts.key($0.name) == RecipeAmounts.key(parts[0]) }
            guard matches.count == 1, let row = matches.first, var value = resolve(row) else { return nil }
            if parts.count == 2 {
                if let divisor = Self.literal(parts[1]), divisor.unit.isEmpty || divisor.unit == value.unit {
                    guard let divided = value.divided(by: divisor.lower, divisor.upper) else { return nil }
                    value = divided
                } else {
                    // 鸡蛋/个：只有单位吻合才引用其实际个数（含取整后的结果）。
                    guard Self.unit(parts[1]) == value.unit, !value.unit.isEmpty else { return nil }
                    value.unit = ""
                }
            }
            // 配方引用的是该材料的数量，最终单位由本行的常数给出。
            if scalarReference { value.unit = "" }
            value.annotations = []
            value.note = nil
            return value
        }

        static func literal(_ text: String) -> Value? {
            let numberPattern = #"(?:[0-9]+(?:\.[0-9]+)?|[一二三四五六七八九十两半])"#
            let unitPattern = #"(kg|KG|g|G|ml|mL|ML|L|l|克|千克|公斤|斤|毫升|升|个|颗|只|根|瓣|片|朵|张|支|块|勺|汤匙|茶匙|把|杯)?"#
            if let parts = match("^(\(numberPattern))(?:\\s*[-–~～至]\\s*(\(numberPattern)))?\\s*\(unitPattern)$", in: text.trimmingCharacters(in: .whitespaces)),
               let lower = number(parts[1]), let upper = parts[2].isEmpty ? lower : number(parts[2]) {
                let result = Value(lower: lower, upper: upper, unit: unit(parts[3]))
                return result.isValid ? result : nil
            }
            if let parts = match("^(\(numberPattern))\\s*/\\s*(\(numberPattern))\\s*\(unitPattern)$", in: text),
               let numerator = number(parts[1]), let denominator = number(parts[2]), denominator > 0 {
                return Value(lower: numerator / denominator, upper: numerator / denominator, unit: unit(parts[3]))
            }
            return nil
        }

        private static func number(_ value: String) -> Double? {
            let chinese: [String: Double] = ["一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10, "半": 0.5]
            return chinese[value] ?? Double(value)
        }

        private static func unit(_ value: String) -> String {
            switch value {
            case "g", "G", "克": return "g"
            case "kg", "KG", "千克", "公斤": return "kg"
            case "ml", "mL", "ML", "毫升": return "ml"
            case "L", "l", "升": return "L"
            default: return value
            }
        }
    }
}

private func format(_ value: Double) -> String {
    if value > 0, value < 0.000001 { return String(format: "%.3g", value) }
    let formatter = NumberFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.usesGroupingSeparator = false
    formatter.maximumFractionDigits = 6
    formatter.minimumIntegerDigits = 1
    return formatter.string(from: NSNumber(value: value)) ?? String(value)
}

private func matches(_ pattern: String, in text: String) -> [[String]] {
    guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
    let text = text as NSString
    return regex.matches(in: text as String, range: NSRange(location: 0, length: text.length)).map { match in
        (0 ..< match.numberOfRanges).map { index in
            let range = match.range(at: index)
            return range.location == NSNotFound ? "" : text.substring(with: range)
        }
    }
}

private func match(_ pattern: String, in text: String) -> [String]? {
    matches(pattern, in: text).first
}
