import CookCore
import SwiftUI

/// Show the exact source recipe, including its limits, in ordinary and filtered details alike.
struct InstitutionalLowGIReviewSection: View {
    let review: InstitutionalLowGICatalog.Entry
    let sourceURL: URL

    var body: some View {
        Text("机构低 GI 食谱").font(.headline)
            .accessibilityIdentifier("institutionalLowGI.title")
        Text(InstitutionalLowGICatalog.explanation)
            .font(.subheadline)
            .fixedSize(horizontal: false, vertical: true)
        LabeledContent("来源", value: InstitutionalLowGICatalog.publisher)
        LabeledContent("原方", value: review.sourceRecipeTitle)
        LabeledContent("作者", value: review.author)
        LabeledContent("核对日期", value: review.reviewedAt)
        Text(review.requirements)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityIdentifier("institutionalLowGI.requirements")
        Link(destination: sourceURL) {
            Label("打开机构原方（英文，需联网）", systemImage: "arrow.up.right.square")
        }
        .accessibilityIdentifier("institutionalLowGI.openSource")
        Link("机构来源与审核说明", destination: URL(string: InstitutionalLowGICatalog.authorityURL)!)
    }
}
