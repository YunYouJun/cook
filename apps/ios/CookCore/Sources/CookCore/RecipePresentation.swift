import Foundation

/// 从明确的章节提取展示信息；无法识别的内容始终交回原文渲染，不推算用量或热量。
public struct RecipePresentation: Sendable {
    public struct Quantity: Hashable, Sendable {
        public let name: String
        public let amount: String
        let annotation: RecipeAmountAnnotations.Quantity?

        init(annotation: RecipeAmountAnnotations.Quantity) {
            name = annotation.name
            amount = annotation.evidence.map(\.text).joined(separator: "；")
            self.annotation = annotation
        }

        public init?(_ text: String) {
            // 只拆明确的等式，保留取整、范围、单位、份数与补充说明。
            guard let separator = text.firstIndex(where: { $0 == "=" || $0 == "＝" }) else { return nil }
            let name = text[..<separator].trimmingCharacters(in: .whitespacesAndNewlines)
            let amount = text[text.index(after: separator)...].trimmingCharacters(in: .whitespacesAndNewlines)
            guard !name.isEmpty, !amount.isEmpty, !name.contains("\n") else { return nil }
            self.name = name
            self.amount = amount
            annotation = nil
        }
    }

    public struct Step: Hashable, Sendable, Identifiable {
        public let id: String
        public let number: Int
        public let text: String
        public let sourceBlock: Int
        public let sourceItem: Int?
    }

    public let amountAnnotations: RecipeAmountAnnotations.Entry?
    public var servingDescription: String? { amountAnnotations?.servingDescription }

    public private(set) var difficulty: Int?
    public private(set) var calories: String?
    public private(set) var metadataBlocks: Set<Int> = []
    public private(set) var ingredientBlocks: Set<Int> = []
    public private(set) var ingredients: [String] = []
    public private(set) var quantities: [Int: [Quantity]] = [:]
    public private(set) var stepsByBlock: [Int: [Step]] = [:]
    public private(set) var steps: [Step] = []

    public init(text: RecipeText) {
        amountAnnotations = text.amountAnnotations?.validated(for: text)
        let annotatedStepHeadings = Set(amountAnnotations?.stepSections.map(\.text) ?? [])
        enum Section { case other, ingredients, quantities, steps, annotatedSteps }
        var section = Section.other
        var ingredientHeading: Int?
        var ingredientHasNotes = false
        var occurrences: [String: Int] = [:]
        var annotatedOccurrences: [String: Int] = [:]
        for (offset, block) in text.blocks.enumerated() {
            switch block {
            case let .heading(title):
                switch title.trimmingCharacters(in: .whitespacesAndNewlines) {
                case "必备原料和工具":
                    section = .ingredients
                    ingredientHeading = offset
                case "计算": section = .quantities
                case "操作", "操作步骤", "制作步骤", "步骤", "做法": section = .steps
                default: section = annotatedStepHeadings.contains(title) ? .annotatedSteps : .other
                }
            case let .paragraph(value):
                let value = value.trimmingCharacters(in: .whitespacesAndNewlines)
                if section == .ingredients, !value.isEmpty { ingredientHasNotes = true }
                if section == .other, difficulty == nil,
                   let stars = Self.value(value, after: "预估烹饪难度"),
                   !stars.isEmpty, stars.allSatisfy({ $0 == "★" }), (1 ... 5).contains(stars.count) {
                    difficulty = stars.count
                    metadataBlocks.insert(offset)
                } else if section == .other, calories == nil,
                          let energy = Self.value(value, after: "预估卡路里"), !energy.isEmpty,
                          !energy.contains("\n") {
                    calories = energy
                    metadataBlocks.insert(offset)
                } else if !value.isEmpty {
                    if section == .steps { appendSteps([value], at: offset, occurrences: &occurrences, sourceItems: false) }
                    if section == .annotatedSteps { appendSteps([value], at: offset, occurrences: &annotatedOccurrences, identityPrefix: "annotated|", sourceItems: false) }
                }
            case let .list(ordered, start, items):
                switch section {
                case .ingredients:
                    ingredients += items
                    ingredientBlocks.insert(offset)
                case .quantities:
                    let rows = items.compactMap(Quantity.init)
                    // 混合说明列表不强行变表格，避免丢失原文中的条件。
                    if !rows.isEmpty, rows.count == items.count { quantities[offset] = rows }
                case .steps:
                    appendSteps(items, at: offset, startingAt: ordered ? start : nil, occurrences: &occurrences)
                case .annotatedSteps:
                    appendSteps(items, at: offset, startingAt: ordered ? start : nil, occurrences: &annotatedOccurrences, identityPrefix: "annotated|")
                case .other: break
                }
            case .image:
                if section == .ingredients { ingredientHasNotes = true }
            }
        }
        var seen: Set<String> = []
        ingredients = ingredients.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
        if let ingredientHeading, !ingredients.isEmpty, !ingredientHasNotes {
            ingredientBlocks.insert(ingredientHeading)
        }
    }

    public var allQuantities: [Quantity] {
        let original = quantities.keys.sorted().flatMap { quantities[$0] ?? [] }
        guard let amountAnnotations else { return original }
        let annotated = amountAnnotations.quantities.map { Quantity(annotation: $0) }
        let names = Set(annotated.map { RecipeAmounts.key($0.name) })
        return original.filter { !names.contains(RecipeAmounts.key($0.name)) } + annotated
    }

    /// 补充面板只展示原用量表以外的确定项，原有正文仍逐块保留。
    public var supplementalQuantities: [Quantity] {
        let originalNames = Set(quantities.values.flatMap { $0 }.map { RecipeAmounts.key($0.name) })
        return allQuantities.filter { $0.annotation != nil && !originalNames.contains(RecipeAmounts.key($0.name)) }
    }

    /// 用原名精确对应用量，去掉可选标签；不靠子串把不同原料误合并。
    public func amount(for ingredient: String) -> String? {
        allQuantities.first { Self.ingredientName($0.name) == Self.ingredientName(ingredient) }?.amount
    }

    public static func ingredientName(_ value: String) -> String {
        value.replacingOccurrences(of: "（可选）", with: "")
            .replacingOccurrences(of: "(可选)", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func value(_ text: String, after prefix: String) -> String? {
        for separator in ["：", ":"] where text.hasPrefix(prefix + separator) {
            return String(text.dropFirst(prefix.count + 1)).trimmingCharacters(in: .whitespaces)
        }
        return nil
    }

    private mutating func appendSteps(_ items: [String], at offset: Int, startingAt start: Int? = nil, occurrences: inout [String: Int], identityPrefix: String = "", sourceItems: Bool = true) {
        for (index, item) in items.enumerated() {
            occurrences[item, default: 0] += 1
            // 内容变化后旧勾选不套到新步骤；相同文字在同一道菜中出现多次也分别记录。
            let step = Step(id: "\(identityPrefix)\(occurrences[item]!)|\(item)", number: start.map { $0 + index } ?? (steps.count + 1), text: item, sourceBlock: offset, sourceItem: sourceItems ? index : nil)
            steps.append(step)
            stepsByBlock[offset, default: []].append(step)
        }
    }
}
