import SwiftUI

/// 示例只说明录入方式，不创建记录或替用户开启提醒。
struct FridgeProduceExamples: View {
    var body: some View {
        Section {
            ForEach(FridgeProduceExample.Kind.allCases) { kind in
                FridgeProduceExample(kind)
            }
        } header: {
            Text("蔬果记录示例")
        } footer: {
            Text("确认食材和储存状态后，再采用建议日期。有包装说明时请先核对包装。")
        }
    }
}

struct FridgeProduceExample: View {
    enum Kind: String, CaseIterable, Identifiable {
        case blueberry = "蓝莓"
        case watermelon = "西瓜"

        var id: String { rawValue }
        var title: String {
            switch self {
            case .blueberry: return "🫐 蓝莓：没有生产日期怎么记？"
            case .watermelon: return "🍉 西瓜：完整和切开怎么记？"
            }
        }
        var accessibilityID: String {
            switch self {
            case .blueberry: return "fridge.example.blueberry"
            case .watermelon: return "fridge.example.watermelon"
            }
        }
    }

    let kind: Kind
    @State private var expanded: Bool

    init(_ kind: Kind, expanded: Bool = false) {
        self.kind = kind
        _expanded = State(initialValue: expanded)
    }

    var body: some View {
        DisclosureGroup(isExpanded: $expanded) {
            switch kind {
            case .blueberry:
                Text("例如今天买回一盒蓝莓：拍清蓝莓，有标签时一起拍入；确认名称为“蓝莓”，购买日期填实际买回的那天。")
                if let advice = ProduceStorageAdvice.recommendation(for: kind.rawValue) {
                    Text("完整蓝莓保持干燥、冷藏在 4℃ 或以下，食用前再清洗。采用食用时间建议后，默认从购买当天起安排 \(advice.days) 天内吃完；日期可以自行调整。")
                }
            case .watermelon:
                Text("完整西瓜：先记录购买日期，再按成熟度自行设定食用计划。")
                Text("已经切开：在确认页打开“已经切开或去皮”，把“切开日期”填为实际切开的那天，再点“采用食用时间建议”。")
                if let advice = ProduceStorageAdvice.recommendation(for: kind.rawValue, cut: true) {
                    Text("例如今天切开半个西瓜：在 2 小时内放入清洁、有盖容器冷藏，保持 4℃ 或以下，默认从今天起安排 \(advice.days) 天内吃完。")
                }
            }
            Link("查看示例的储存依据", destination: ProduceStorageAdvice.source)
        } label: {
            Text(kind.title)
        }
        .accessibilityIdentifier(kind.accessibilityID)
    }
}

#Preview("蔬果记录示例") {
    Form { FridgeProduceExamples() }
}
