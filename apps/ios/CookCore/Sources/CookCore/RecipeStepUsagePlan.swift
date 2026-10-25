import Foundation

/// 数值与单位始终独立保存，不能从「20 ml」等界面文案倒推总量。
struct RecipeMeasurement: Equatable, Sendable {
    let lower: Double
    let upper: Double
    let unit: String

    init?(lower: Double, upper: Double, unit: String) {
        guard lower.isFinite, upper.isFinite, lower >= 0, upper >= lower, !unit.isEmpty else { return nil }
        self.lower = lower
        self.upper = upper
        self.unit = unit
    }

    private var base: (unit: String, factor: Double) {
        switch unit {
        case "kg": return ("g", 1000)
        case "L": return ("ml", 1000)
        default: return (unit, 1)
        }
    }

    func adding(_ other: Self) -> Self? {
        guard base.unit == other.base.unit else { return nil }
        let factor = other.base.factor / base.factor
        return Self(lower: lower + other.lower * factor, upper: upper + other.upper * factor, unit: unit)
    }

    func remaining(after uses: [Self]) -> Self? {
        var used = 0.0
        for value in uses {
            // 前序范围并未指定实际取了多少，不能替用户选上下限。
            guard value.lower == value.upper, value.base.unit == base.unit else { return nil }
            used += value.lower * value.base.factor / base.factor
        }
        guard used.isFinite, used <= lower + 1e-9 else { return nil }
        return Self(lower: max(0, lower - used), upper: max(0, upper - used), unit: unit)
    }

    var text: String {
        func number(_ value: Double) -> String {
            if value > 0, value < 0.000001 { return String(format: "%.3g", value) }
            let formatter = NumberFormatter()
            formatter.locale = Locale(identifier: "en_US_POSIX")
            formatter.usesGroupingSeparator = false
            formatter.minimumIntegerDigits = 1
            formatter.maximumFractionDigits = 6
            return formatter.string(from: NSNumber(value: value)) ?? String(value)
        }
        return (lower == upper ? number(lower) : "\(number(lower))–\(number(upper))") + " " + unit
    }
}

/// 依菜谱原始顺序建立投料关系。勾选步骤只是进度，不会再次扣料或改变后续提示。
public struct RecipeStepUsagePlan: Sendable {
    private var instructions: [String: RecipeInstructionAmounts] = [:]

    public init(steps: [RecipePresentation.Step], amounts: RecipeAmounts, annotations: RecipeAmountAnnotations.Entry? = nil) {
        var rendered = steps.map { amounts.instruction($0.text) }
        var annotatedEvents: [Use] = []
        let annotatedNames = Set(annotations?.usages.map(\.ingredient) ?? [])
        let blocked = Set(annotations?.blockedIngredients ?? [])
        for (index, step) in steps.enumerated() {
            let entries = (annotations?.usages ?? []).filter { $0.anchor.block == step.sourceBlock && $0.anchor.item == step.sourceItem }
            var source = step.text
            var adjusted = false
            var unresolved = false
            // 每个明确数量片段在原句中必须唯一；保留片段并补充实际量，不改变步骤 id。
            for entry in entries where entry.kind == "amount" {
                if let formula = entry.formula, let value = amounts.measurement(formula), let target = entry.target,
                   source.components(separatedBy: target).count == 2 {
                    if entry.display != false, RecipeAmounts.literalMeasurement(amounts.instruction(target).text) != value {
                        source = source.replacingOccurrences(of: target, with: target + "（本步 \(entry.ingredient) \(value.text)）")
                        adjusted = true
                    }
                    annotatedEvents.append(Use(ingredient: entry.ingredient, step: index, range: NSRange(location: (step.text as NSString).range(of: target).location, length: target.utf16.count), insertion: 0, kind: .explicit(value), conditional: entry.conditional || amounts.isConditional(formula)))
                } else { unresolved = true }
            }
            rendered[index] = amounts.instruction(source).withUsageText(amounts.instruction(source).text, adjusted: adjusted, unresolved: unresolved)
            for entry in entries where entry.kind != "amount" && entry.kind != "reference" {
                let range = entry.target.map { (rendered[index].text as NSString).range(of: $0) } ?? NSRange(location: 0, length: 0)
                let kind: Kind = entry.kind == "total" ? .whole : entry.kind == "remaining" ? .remaining : .unknown
                if range.location != NSNotFound {
                    annotatedEvents.append(Use(ingredient: entry.ingredient, step: index, range: range, insertion: NSMaxRange(range), kind: kind, conditional: entry.conditional))
                }
            }
            // 标注只覆盖它指向的使用点，不能隐藏同一食材在别处的未标注投料。
            // 未覆盖的引用只阻断余量推定，不猜测它是否真的是额外消耗。
            for event in Self.uses(in: step.text, step: index, names: amounts.ingredientNames)
                where annotatedNames.contains(event.ingredient) {
                let covered = entries.contains { entry in
                    guard entry.ingredient == event.ingredient else { return false }
                    if entry.kind == "unknown" { return true }
                    guard let target = entry.target else { return false }
                    let range = (step.text as NSString).range(of: target)
                    return NSIntersectionRange(range, event.range).length > 0
                        || step.text.components(separatedBy: event.ingredient).count == 2
                }
                if !covered {
                    annotatedEvents.append(Use(ingredient: event.ingredient, step: index, range: event.range,
                                               insertion: 0, kind: .unknown, conditional: true))
                }
            }
        }
        let uses = (rendered.enumerated().flatMap { pair in
            Self.uses(in: pair.element.text, step: pair.offset, names: amounts.ingredientNames).map { use in
                blocked.contains(use.ingredient) ? Use(ingredient: use.ingredient, step: use.step, range: use.range, insertion: use.insertion, kind: .unknown, conditional: true) : use
            }.filter { !annotatedNames.contains($0.ingredient) }
        } + annotatedEvents).sorted { $0.step == $1.step ? $0.range.location < $1.range.location : $0.step < $1.step }
        var insertions: [Int: [(offset: Int, text: String)]] = [:]
        var unresolved: Set<Int> = []
        for (name, events) in Dictionary(grouping: uses, by: \.ingredient) {
            let total = amounts.amount(for: name)?.measurement
            var previous: [RecipeMeasurement] = []
            var uncertain = false
            for (index, event) in events.enumerated() {
                switch event.kind {
                case let .explicit(value):
                    previous.append(value)
                case .whole, .remaining:
                    let value: RecipeMeasurement?
                    if case .whole = event.kind {
                        // 仅一个明确投料位置时，才把整份原料放到这一步。
                        value = events.count == 1 ? total : nil
                    } else {
                        value = !uncertain && !previous.isEmpty && index == events.count - 1
                            ? total?.remaining(after: previous) : nil
                    }
                    if let value {
                        insertions[event.step, default: []].append((event.insertion, "（\(value.text)）"))
                    } else {
                        unresolved.insert(event.step)
                    }
                    uncertain = true
                case .unknown:
                    uncertain = true
                    unresolved.insert(event.step)
                }
                if event.conditional { uncertain = true }
            }
        }
        for (index, step) in steps.enumerated() {
            let result = NSMutableString(string: rendered[index].text)
            for insertion in (insertions[index] ?? []).sorted(by: { $0.offset > $1.offset }) {
                result.insert(insertion.text, at: insertion.offset)
            }
            instructions[step.id] = rendered[index].withUsageText(result as String,
                adjusted: insertions[index] != nil, unresolved: unresolved.contains(index))
        }
    }

    public func instruction(for step: RecipePresentation.Step) -> RecipeInstructionAmounts? { instructions[step.id] }

    private enum Kind { case explicit(RecipeMeasurement), whole, remaining, unknown }
    private struct Use {
        let ingredient: String
        let step: Int
        let range: NSRange
        let insertion: Int
        let kind: Kind
        let conditional: Bool
    }

    private static func uses(in text: String, step: Int, names: [String]) -> [Use] {
        let source = text as NSString
        let links = matches(#"!?\[[^\]\n]*\]\([^\)\n]*\)|https?://[^\s<>]+"#, in: text).map(\.range)
        var result: [Use] = []
        for clauseRange in split(source, separators: "，,。；;\n") {
            let clause = source.substring(with: clauseRange)
            let verbs = matches("加入|放入|倒入|撒入|撒上|淋入|下入|加进|加上", in: clause)
            let mentions = names.flatMap { name in
                matches(NSRegularExpression.escapedPattern(for: name), in: clause).map { (name, $0.range) }
            }.filter { mention in
                let global = NSRange(location: clauseRange.location + mention.1.location, length: mention.1.length)
                return !links.contains { NSIntersectionRange($0, global).length > 0 }
            }
            var recognized: Set<Int> = []
            for (verbIndex, verb) in verbs.enumerated() {
                let start = NSMaxRange(verb.range)
                let end = verbIndex + 1 < verbs.count ? verbs[verbIndex + 1].range.location : (clause as NSString).length
                let objects = (clause as NSString).substring(with: NSRange(location: start, length: end - start)) as NSString
                for part in split(objects, separators: "、和及") {
                    let object = objects.substring(with: part)
                    let objectStart = start + part.location
                    let localMentions = mentions.filter { $0.1.location >= objectStart && NSMaxRange($0.1) <= objectStart + part.length }
                    // 名称有重叠时优先完整的长名称，不把「食盐」当成另一份「盐」。
                    let candidates = localMentions.filter { item in
                        !localMentions.contains { other in other.1.length > item.1.length && NSIntersectionRange(item.1, other.1) == item.1 }
                    }
                    guard candidates.count == 1, let (name, localRange) = candidates.first else { continue }
                    let nameRange = NSRange(location: localRange.location - objectStart, length: localRange.length)
                    let raw = object as NSString
                    let prefix = plain(raw.substring(to: nameRange.location))
                    let suffix = plain(raw.substring(from: NSMaxRange(nameRange)))
                    let prefixWithoutRemaining = prefix.replacingOccurrences(of: #"^(剩余|剩下|全部)的?\s*"#, with: "", options: .regularExpression)
                    let quantityPrefix = prefixWithoutRemaining.replacingOccurrences(of: #"\s*的$"#, with: "", options: .regularExpression)
                    let quantitySuffix = suffix.replacingOccurrences(of: #"[（(][^（）()]*[）)]"#, with: "", options: .regularExpression).trimmingCharacters(in: .whitespaces)
                    let beforeVerb = (clause as NSString).substring(to: verb.range.location)
                    let context = source.substring(to: clauseRange.location + objectStart) + object
                    let conditional = ["如果", "若", "可以", "可选", "按需", "否则", "或者"].contains { context.contains($0) }
                    let forbidden = ["不要", "不能", "无需", "不必", "勿"].contains { beforeVerb.contains($0) }
                    let kind: Kind
                    if forbidden { kind = .unknown }
                    else if let measured = RecipeAmounts.literalMeasurement(quantityPrefix), quantitySuffix.isEmpty { kind = .explicit(measured) }
                    else if let measured = RecipeAmounts.literalMeasurement(quantitySuffix), quantityPrefix.isEmpty { kind = .explicit(measured) }
                    else if quantityPrefix.isEmpty, quantitySuffix.isEmpty {
                        kind = prefix.hasPrefix("剩余") || prefix.hasPrefix("剩下") ? .remaining : .whole
                    } else { kind = .unknown }
                    let global = NSRange(location: clauseRange.location + localRange.location, length: localRange.length)
                    var insertion = NSMaxRange(global)
                    // 注释加在名称的 Markdown 标记之后，保留原来的强调和可选说明。
                    while insertion < source.length, source.character(at: insertion) == 96 || source.character(at: insertion) == 42 { insertion += 1 }
                    result.append(Use(ingredient: name, step: step, range: global, insertion: insertion, kind: kind, conditional: conditional))
                    recognized.insert(localRange.location)
                }
            }
            // 其他动词或「少许/分次/适量」等不明确用法会阻止后续余量推算。
            if !matches("加|放|倒|撒|淋|腌|拌|用|分|取|打入", in: clause).isEmpty {
                for (name, range) in mentions where !recognized.contains(range.location) {
                    let global = NSRange(location: clauseRange.location + range.location, length: range.length)
                    result.append(Use(ingredient: name, step: step, range: global, insertion: NSMaxRange(global), kind: .unknown, conditional: true))
                }
                // 缩略名只用于发现潜在分次投料，不把不同油品或调料自动合并。
                for (name, alias) in [("食用油", "油"), ("食盐", "盐"), ("食用盐", "盐")] where names.contains(name) {
                    for match in matches(alias, in: clause) where !mentions.contains(where: { NSIntersectionRange($0.1, match.range).length > 0 }) {
                        let global = NSRange(location: clauseRange.location + match.range.location, length: match.range.length)
                        guard !links.contains(where: { NSIntersectionRange($0, global).length > 0 }) else { continue }
                        result.append(Use(ingredient: name, step: step, range: global, insertion: NSMaxRange(global), kind: .unknown, conditional: true))
                    }
                }
            }
        }
        return result.sorted { $0.range.location < $1.range.location }
    }

    private static func plain(_ text: String) -> String {
        text.replacingOccurrences(of: "`", with: "").replacingOccurrences(of: "**", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func matches(_ pattern: String, in text: String) -> [NSTextCheckingResult] {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return [] }
        return regex.matches(in: text, range: NSRange(location: 0, length: (text as NSString).length))
    }

    private static func split(_ source: NSString, separators: String) -> [NSRange] {
        var depth = 0, start = 0
        var ranges: [NSRange] = []
        for index in 0 ..< source.length {
            let character = source.character(at: index)
            if character == 40 || character == 65288 { depth += 1 }
            if character == 41 || character == 65289 { depth = max(0, depth - 1) }
            if depth == 0, separators.utf16.contains(character) {
                ranges.append(NSRange(location: start, length: index - start))
                start = index + 1
            }
        }
        ranges.append(NSRange(location: start, length: source.length - start))
        return ranges
    }
}
