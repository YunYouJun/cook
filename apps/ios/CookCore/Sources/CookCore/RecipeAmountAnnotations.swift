import Foundation

/// 独立于正文的用量证据。整篇指纹、逐条锚点和语义校验全部通过才采用；否则整菜回退到原文规则。
public struct RecipeAmountAnnotations: Decodable, Sendable {
    public struct Anchor: Decodable, Hashable, Sendable {
        public let block: Int
        public let item: Int?
        public let text: String

        func matches(_ source: RecipeText) -> Bool {
            guard source.blocks.indices.contains(block) else { return false }
            switch source.blocks[block] {
            case let .heading(value), let .paragraph(value): return item == nil && value == text
            case let .list(_, _, items): return item.map { items.indices.contains($0) && items[$0] == text } ?? false
            case .image: return false
            }
        }
    }

    /// 封闭的运算树，不执行任意代码；引用值包含已经执行的取整。
    public struct Formula: Decodable, Hashable, Sendable {
        public let op: String
        public let lower: Double?
        public let upper: Double?
        public let unit: String?
        public let name: String?
        public let args: [Formula]?

        private static let units: Set<String> = ["g", "kg", "ml", "L", "斤", "cm", "个", "颗", "只", "根", "瓣", "片", "朵", "张", "支", "块", "勺", "汤匙", "茶匙", "把", "杯", "袋", "包", "盒", "瓶", "罐", "枚", "粒", "棵", "条", "碗", "滴", "扎", "圈", "节", "段", "株", "叶", "卷", "头", "份", "小块", "瓶盖"]
        var isValid: Bool { valid(depth: 0) }
        private func valid(depth: Int) -> Bool {
            guard depth < 16 else { return false }
            switch op {
            case "literal":
                guard let lower, let unit, Self.units.contains(unit), lower.isFinite, lower >= 0,
                      let measurement = RecipeMeasurement(lower: lower, upper: upper ?? lower, unit: unit) else { return false }
                return measurement.lower == lower && name == nil && args == nil
            case "servings": return lower == nil && upper == nil && unit == nil && name == nil && args == nil
            case "scalar": return lower.map { $0.isFinite && $0 >= 0 } == true && upper == nil && unit == nil && name == nil && args == nil
            case "reference": return name.map { !$0.isEmpty } == true && lower == nil && upper == nil && args == nil && unit == nil
            case "count": return name.map { !$0.isEmpty } == true && unit.map { Self.units.contains($0) } == true && lower == nil && upper == nil && args == nil
            case "multiply", "divide", "add":
                return lower == nil && upper == nil && unit == nil && name == nil && args?.count == 2 && args!.allSatisfy { $0.valid(depth: depth + 1) }
            case "ceil", "floor":
                return lower == nil && upper == nil && unit == nil && name == nil && args?.count == 1 && args![0].valid(depth: depth + 1)
            default: return false
            }
        }
    }

    public struct Quantity: Decodable, Hashable, Sendable {
        public let name: String
        public let formula: Formula
        public let evidence: [Anchor]
        public let aliases: [String]
        /// 原文的限定语完整保留，条件用量不参与无条件余量扣减。
        public let note: String?
        public let conditional: Bool
    }

    public struct Usage: Decodable, Hashable, Sendable {
        public let anchor: Anchor
        public let ingredient: String
        /// reference 是有原文依据的切配/半成品引用，不再次消耗；unknown 阻断自动推定。
        public let kind: String
        public let formula: Formula?
        public let target: String?
        public let display: Bool?
        public let conditional: Bool
        public let evidence: [Anchor]
    }

    public struct Entry: Decodable, Hashable, Sendable {
        public let sourceFingerprint: String
        public let servingEvidence: [Anchor]
        public let servingDescription: String?
        public let referenceServings: Int?
        public let quantities: [Quantity]
        public let stepSections: [Anchor]
        public let usages: [Usage]
        public let blockedIngredients: [String]

        func validated(for source: RecipeText) -> Self? {
            guard referenceServings.map({ RecipeAmounts.servingRange.contains($0) }) ?? true,
                  sourceFingerprint == RecipeAmountAnnotations.fingerprint(source),
                  servingEvidence.allSatisfy({ $0.matches(source) }),
                  stepSections.allSatisfy({ anchor in
                      anchor.matches(source) && { if case .heading = source.blocks[anchor.block] { return true }; return false }()
                  }),
                  Set(quantities.map { RecipeAmounts.key($0.name) }).count == quantities.count,
                  Set(usages.map { "\($0.anchor.block):\($0.anchor.item ?? -1):\($0.ingredient):\($0.kind):\($0.target ?? "")" }).count == usages.count,
                  quantities.allSatisfy({ !$0.name.isEmpty && $0.formula.isValid && !$0.evidence.isEmpty && $0.evidence.allSatisfy { $0.matches(source) } }),
                  usages.allSatisfy({ use in
                      use.anchor.matches(source) && !use.ingredient.isEmpty && ["amount", "total", "remaining", "unknown", "reference"].contains(use.kind)
                          && (use.kind != "amount" || use.formula?.isValid == true)
                          && !use.evidence.isEmpty && use.evidence.allSatisfy { $0.matches(source) }
                          && (use.kind == "unknown" || use.target.map { !$0.isEmpty && use.anchor.text.components(separatedBy: $0).count == 2 } == true)
                  }) else { return nil }
            // 别名必须唯一，不能把同一名称映射给不同材料。
            var owners: [String: String] = [:]
            for quantity in quantities {
                for name in [quantity.name] + quantity.aliases {
                    guard !name.isEmpty, owners[name] == nil || owners[name] == quantity.name else { return nil }
                    owners[name] = quantity.name
                }
            }
            // 公式必须在支持的份数上完整可求值；循环、未知引用、单位相乘/相加不兼容均整菜回退。
            var original = source
            original.amountAnnotations = nil
            let baseRows = RecipePresentation(text: original).allQuantities
            let replacementNames = Set(quantities.map { RecipeAmounts.key($0.name) })
            let rows = baseRows.filter { !replacementNames.contains(RecipeAmounts.key($0.name)) }
                + quantities.map { RecipePresentation.Quantity(annotation: $0) }
            for servings in RecipeAmounts.servingRange {
                let amounts = RecipeAmounts(rows: rows, servings: servings)
                guard rows.filter({ $0.annotation != nil }).allSatisfy({ amounts.amount(for: $0).isResolved }),
                      usages.filter({ $0.kind == "amount" }).allSatisfy({ $0.formula.flatMap { amounts.measurement($0) } != nil }) else { return nil }
            }
            return self
        }
    }

    public let schemaVersion: Int
    private let recipes: [String: Entry]
    public static let fileName = "recipe-amounts.json"

    public static func load(from directory: URL) -> Self? {
        guard let data = try? Data(contentsOf: directory.appendingPathComponent(fileName)),
              let library = try? JSONDecoder().decode(Self.self, from: data), library.schemaVersion == 1 else { return nil }
        return library
    }

    public func entry(for id: Int, source: RecipeText) -> Entry? { recipes[String(id)]?.validated(for: source) }

    /// FNV-1a 64，UTF-8、NUL 分隔的类型/字段序列。用于检测来源漂移，不作为安全签名。
    public static func fingerprint(_ source: RecipeText) -> String {
        var tokens: [String] = []
        for block in source.blocks {
            switch block {
            case let .heading(text): tokens += ["heading", text]
            case let .paragraph(text): tokens += ["paragraph", text]
            case let .list(ordered, start, items): tokens += ["list", ordered ? "true" : "false", String(start), String(items.count)] + items
            case let .image(src, width, height, alt): tokens += ["image", src, String(width), String(height), alt ?? ""]
            }
        }
        var value: UInt64 = 14695981039346656037
        for byte in tokens.joined(separator: "\0").utf8 { value = (value ^ UInt64(byte)) &* 1099511628211 }
        return String(format: "%016llx", value)
    }
}
