import Foundation

/// 只替换步骤中有明确份数依据的完整用量表达式；来源正文和步骤身份保持不变。
public struct RecipeInstructionAmounts: Equatable, Sendable {
    public let text: String
    public let hasAdjustedAmounts: Bool
    public let hasUnresolvedFormula: Bool

    func withUsageText(_ text: String, adjusted: Bool, unresolved: Bool) -> Self {
        Self(text: text, hasAdjustedAmounts: hasAdjustedAmounts || adjusted,
             hasUnresolvedFormula: hasUnresolvedFormula || unresolved)
    }

    private init(text: String, hasAdjustedAmounts: Bool, hasUnresolvedFormula: Bool) {
        self.text = text
        self.hasAdjustedAmounts = hasAdjustedAmounts
        self.hasUnresolvedFormula = hasUnresolvedFormula
    }

    init(text source: String, evaluate: (String) -> String?) {
        let original = source as NSString
        var protected = Self.links(in: source)
        var edits: [(range: NSRange, text: String)] = []

        func intersects(_ range: NSRange) -> Bool {
            protected.contains { NSIntersectionRange($0, range).length > 0 }
        }
        func replace(_ range: NSRange, expression: String) {
            if let value = evaluate(expression) { edits.append((range, value)) }
        }

        // 保留代码标记和其他 Markdown；无法理解的完整表达式不再拆开尝试。
        for match in Self.matches(#"`([^`\n]+)`"#, in: source) where !intersects(match.range) {
            let content = match.range(at: 1)
            let expression = original.substring(with: content)
            if (expression.contains("*") || expression.contains("×") || expression.contains("/")),
               !Self.hasAdjacentOperation(match.range, in: original) {
                replace(content, expression: expression)
            }
            protected.append(match.range)
        }

        // 处理「（份数 * 5）g」和「（2g * 份数）」；嵌套运算交给求值器拒绝，避免局部误换算。
        var stack: [Int] = []
        for index in 0 ..< original.length {
            let character = original.character(at: index)
            if character == 40 || character == 65288 { stack.append(index) }
            if character == 41 || character == 65289, let start = stack.popLast(), stack.isEmpty {
                let range = NSRange(location: start, length: index - start + 1)
                guard !intersects(range) else { continue }
                let inner = original.substring(with: NSRange(location: start + 1, length: range.length - 2))
                guard inner.contains("份数") || inner.contains("*") || inner.contains("×") else { continue }
                let suffix = original.substring(from: NSMaxRange(range))
                let unit = Self.matches("^\\s*(\(Self.units))(?![A-Za-z])", in: suffix).first
                let complete = NSRange(location: start, length: range.length + (unit?.range.length ?? 0))
                let expression = unit.map { inner + " * 1 " + (suffix as NSString).substring(with: $0.range(at: 1)) } ?? inner
                if !Self.hasAdjacentOperation(complete, in: original) { replace(complete, expression: expression) }
                protected.append(complete)
            }
        }

        let number = #"(?:[0-9]+(?:\.[0-9]+)?|[一二三四五六七八九十两半])"#
        let quantity = "\(number)(?:\\s*(?:/|[-–~～至])\\s*\(number))?\\s*(?:\(Self.units))?(?![A-Za-z0-9.一二三四五六七八九十两半])"
        let atom = "(?:份数|\(quantity))"
        let formula = "\(atom)(?:\\s*[*×/+÷^=−-]\\s*\(atom))+(?:[，,]?\\s*向上取整|\\s*[（(]向上取整[）)])?"
        for match in Self.matches(formula, in: source) where !intersects(match.range) {
            let expression = original.substring(with: match.range)
            guard expression.contains("份数"), !Self.hasAdjacentOperation(match.range, in: original) else { continue }
            replace(match.range, expression: expression)
        }

        let result = NSMutableString(string: source)
        for edit in edits.sorted(by: { $0.range.location > $1.range.location }) {
            result.replaceCharacters(in: edit.range, with: edit.text)
        }
        text = result as String
        hasAdjustedAmounts = !edits.isEmpty
        let links = Self.links(in: text)
        hasUnresolvedFormula = Self.matches(#"份数\s*[*×/+÷^=−-]|[*×/+÷^=−-]\s*份数"#, in: text).contains { match in
            !links.contains { NSIntersectionRange($0, match.range).length > 0 }
        }
    }

    private static let units = "千克|公斤|毫升|汤匙|茶匙|kg|KG|ml|mL|ML|g|G|L|l|克|斤|升|个|颗|只|根|瓣|片|朵|张|支|块|勺|把|杯"

    private static func matches(_ pattern: String, in text: String) -> [NSTextCheckingResult] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: text, range: NSRange(location: 0, length: (text as NSString).length))
    }

    private static func links(in text: String) -> [NSRange] {
        matches(#"!?\[[^\]\n]*\]\([^\)\n]*\)|https?://[^\s<>]+"#, in: text).map(\.range)
    }

    private static func hasAdjacentOperation(_ range: NSRange, in source: NSString) -> Bool {
        let before = source.substring(to: range.location).trimmingCharacters(in: .whitespacesAndNewlines)
        let after = source.substring(from: NSMaxRange(range)).trimmingCharacters(in: .whitespacesAndNewlines)
        // 未包含在候选式中的取整条件也不能忽略，避免把尚需取整的数值显示成最终用量。
        if !matches(#"^[，,]?\s*(?:[（(]\s*)?向[上下]取整"#, in: after).isEmpty { return true }
        // 不把不支持的长表达式（加法、未知依赖、嵌套运算等）截成可计算的小片段。
        let operators = "*×/+÷^=−-()（）"
        if let last = before.last, operators.contains(last) { return true }
        if let first = after.first, operators.contains(first) { return true }
        return false
    }
}
