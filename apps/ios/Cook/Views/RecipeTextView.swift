import CookCore
import SwiftUI

/// 图文做法：读 App 内置的正文离线显示，配图从对象存储加载、点开看大图，右上角可以打开原文
struct RecipeTextView: View {
    let recipe: Recipe
    let text: RecipeText

    var body: some View {
        RecipeTextContent(recipe: recipe, text: text)
            .navigationTitle(recipe.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if let link = recipe.link, !link.isEmpty, let url = URL(string: link) {
                        Link(destination: url) {
                            Label("打开原文", systemImage: "safari")
                        }
                    }
                }
            }
    }
}

/// 阅读页和厨房工作台共用正文、图片查看器与出处，不包含外层导航。
struct RecipeTextContent: View {
    @Environment(RecipeStore.self) private var store
    @Environment(KitchenWorkbench.self) private var workbench
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let recipe: Recipe
    let text: RecipeText
    let preparationInSidebar: Bool
    private let presentation: RecipePresentation

    init(recipe: Recipe, text: RecipeText, preparationInSidebar: Bool = false) {
        self.recipe = recipe
        self.text = text
        self.preparationInSidebar = preparationInSidebar
        presentation = RecipePresentation(text: text)
    }

    private var amounts: RecipeAmounts {
        RecipeAmounts(rows: presentation.allQuantities, servings: workbench.state(for: recipe.id).servings)
    }

    private var canAdjustServings: Bool {
        amounts.supportsServings || presentation.steps.contains { amounts.instruction($0.text).hasAdjustedAmounts }
    }

    private var completed: Set<String> { workbench.state(for: recipe.id).completedSteps }
    private var nextStep: String? { presentation.steps.first { !completed.contains($0.id) }?.id }
    @State private var showsPhotos = false
    @State private var photoSelection = 0
    @State private var stepLocation: StepLocation?
    @Namespace private var photoTransition

    private var originalURL: URL? {
        guard let link = recipe.link, !link.isEmpty else { return nil }
        return URL(string: link)
    }

    /// 正文里的配图，按出现顺序，大图里左右滑动切换
    private var photos: [RecipeTextPhoto] {
        text.blocks.enumerated().compactMap { offset, block in
            guard case let .image(src, width, height, alt) = block else { return nil }
            return makePhoto(at: offset, src: src, width: width, height: height, alt: alt)
        }
    }

    var body: some View {
        let amounts = amounts
        let usage = RecipeStepUsagePlan(steps: presentation.steps, amounts: amounts, annotations: presentation.amountAnnotations)
        ScrollViewReader { proxy in
            let jump: (RecipePresentation.Step) -> Void = { step in
                stepLocation = StepLocation(id: step.id)
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) {
                    proxy.scrollTo(StepAnchor(id: step.id), anchor: .top)
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    RecipeMetricsView(presentation: presentation, difficulty: recipe.difficulty)
                    if let description = presentation.servingDescription {
                        Text(description).font(.footnote).foregroundStyle(.secondary)
                    }
                    if !presentation.supplementalQuantities.isEmpty {
                        if canAdjustServings {
                            RecipeServingControl(recipeID: recipe.id, rows: presentation.allQuantities, identifier: "recipe.servings")
                        }
                        Text("按原文整理的用量").font(.headline)
                        RecipeQuantityTable(rows: presentation.supplementalQuantities, amounts: amounts)
                    }
                    ForEach(Array(text.blocks.enumerated()), id: \.offset) { offset, block in
                        if !presentation.metadataBlocks.contains(offset),
                           !(preparationInSidebar && presentation.ingredientBlocks.contains(offset)) {
                            presentedBlock(block, at: offset, amounts: amounts, usage: usage, jump: jump)
                        }
                    }
                    footer
                        .padding(.top, 12)
                }
                .padding()
                // iPad 上行太长不好读
                .frame(maxWidth: 680, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .scrollEdgeBar(edge: .top) {
                if !presentation.steps.isEmpty {
                    RecipeStepProgress(recipeID: recipe.id, steps: presentation.steps, jump: jump)
                }
            }
            // 大图里翻到别的图时，正文跟着滚过去，收起时缩回那张图的位置
            .onChange(of: photoSelection) { _, id in
                if showsPhotos {
                    proxy.scrollTo(PhotoAnchor(id: id), anchor: .center)
                }
            }
        }
        .task(id: stepLocation) {
            guard stepLocation != nil else { return }
            do { try await Task.sleep(for: .seconds(5)) } catch { return }
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                stepLocation = nil
            }
        }
        .sensoryFeedback(.selection, trigger: completed)
        .fullScreenCover(isPresented: $showsPhotos) {
            RecipeImageViewer(photos: photos, selection: $photoSelection)
                .zoomTransition(sourceID: photoSelection, in: photoTransition)
        }
    }

    @ViewBuilder
    private func presentedBlock(_ block: RecipeTextBlock, at offset: Int, amounts: RecipeAmounts, usage: RecipeStepUsagePlan, jump: @escaping (RecipePresentation.Step) -> Void) -> some View {
        if let steps = presentation.stepsByBlock[offset] {
            if presentation.supplementalQuantities.isEmpty, offset == presentation.stepsByBlock.keys.min(), !amounts.supportsServings, canAdjustServings {
                RecipeServingControl(recipeID: recipe.id, rows: presentation.allQuantities, identifier: "recipe.servings")
                    .padding(16)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
            }
            VStack(spacing: 10) {
                ForEach(steps) { step in
                    RecipeStepCard(step: step, instruction: usage.instruction(for: step), servings: amounts.servings,
                                   highlighted: stepLocation?.id == step.id,
                                   completed: completed.contains(step.id), current: nextStep == step.id) {
                        let advances = nextStep == step.id
                        workbench.toggleStep(step.id, recipeID: recipe.id)
                        if advances, let next = presentation.steps.first(where: { !completed.contains($0.id) }) {
                            jump(next)
                        }
                    }
                    .id(StepAnchor(id: step.id))
                }
            }
        } else if let rows = presentation.quantities[offset] {
            if presentation.supplementalQuantities.isEmpty, offset == presentation.quantities.keys.min(), amounts.supportsServings {
                RecipeServingControl(recipeID: recipe.id, rows: presentation.allQuantities, identifier: "recipe.servings")
                    .padding(16)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
            }
            RecipeQuantityTable(rows: rows, amounts: amounts)
        } else if presentation.ingredientBlocks.contains(offset), case let .list(_, _, items) = block {
            VStack(alignment: .leading, spacing: 12) {
                ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                    HStack(spacing: 12) {
                        RecipeIngredientIcon(name: item, isTool: KitchenIngredients.isTool(item))
                        Text(recipeMarkdown(item))
                    }
                }
            }
        } else {
            blockView(block, at: offset, jump: jump)
                .textSelection(.enabled)
        }
    }

    @ViewBuilder
    private func blockView(_ block: RecipeTextBlock, at offset: Int, jump: @escaping (RecipePresentation.Step) -> Void) -> some View {
        switch block {
        case let .heading(heading):
            if heading.trimmingCharacters(in: .whitespacesAndNewlines) == "附加内容" {
                RecipeSupplementaryHeading()
            } else {
                Text(markdown(heading == "计算" ? "用量参考" : heading))
                    .font(.headline)
                    .padding(.top, 12)
                    .accessibilityAddTraits(.isHeader)
            }
        case let .paragraph(paragraph):
            if !paragraph.isEmpty {
                if RecipeSupplementaryContent.contains(offset, in: text.blocks) {
                    if offset + 1 < text.blocks.count, case .list = text.blocks[offset + 1] {
                        Text(markdown(paragraph))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineSpacing(3)
                    } else {
                        RecipeSupplementaryCard(text: paragraph, steps: presentation.steps, jump: jump)
                    }
                } else {
                    Text(markdown(paragraph))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineSpacing(3)
                }
            }
        case let .list(ordered, start, items):
            if RecipeSupplementaryContent.contains(offset, in: text.blocks) {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        RecipeSupplementaryCard(text: item, number: ordered ? start + index : nil, steps: presentation.steps, jump: jump)
                    }
                }
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(ordered ? "\(start + index)." : "•")
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                            Text(markdown(item))
                        }
                    }
                }
            }
        case let .image(src, width, height, alt):
            if let photo = makePhoto(at: offset, src: src, width: width, height: height, alt: alt) {
                RecipeTextImage(photo: photo, transition: photoTransition) {
                    photoSelection = photo.id
                    showsPhotos = true
                }
                .id(PhotoAnchor(id: photo.id))
            }
        }
    }

    private func makePhoto(at offset: Int, src: String, width: Int, height: Int, alt: String?) -> RecipeTextPhoto? {
        guard let url = store.textImageURL(src) else { return nil }
        let description = meaningfulAlt(alt)
        // 和上一段正文重复的说明（「碗 1」「改刀」）不再显示在图下，只留给旁白
        let caption = description.flatMap { precedingText(offset).contains($0) ? nil : $0 }
        return RecipeTextPhoto(
            id: offset,
            url: url,
            aspectRatio: CGFloat(width) / CGFloat(max(height, 1)),
            caption: caption,
            accessibilityLabel: description ?? "\(recipe.name)的配图"
        )
    }

    /// 原文的图片说明。「XX-预览图-1」「红烧肉成品」这种只是文件名或菜名，英文的多是图片来源，都不算
    private func meaningfulAlt(_ alt: String?) -> String? {
        guard let alt = alt?.trimmingCharacters(in: .whitespaces), !alt.isEmpty else { return nil }
        let isChinese = alt.unicodeScalars.contains { (0x4E00 ... 0x9FFF).contains($0.value) }
        guard isChinese, !alt.contains("预览图"), !alt.hasSuffix("成品"), !alt.contains(recipe.name) else { return nil }
        return alt
    }

    /// 配图前面紧挨着的那段文字
    private func precedingText(_ offset: Int) -> String {
        guard offset > 0 else { return "" }
        switch text.blocks[offset - 1] {
        case let .heading(string), let .paragraph(string):
            return string
        case let .list(_, _, items):
            return items.joined(separator: "\n")
        case .image:
            return ""
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            // 没配置配图地址的数据包不显示图片，提示去原文看
            if text.imageCount > 0, !store.showsTextImages {
                Text("原文还有 \(text.imageCount) 张配图。")
            }
            if let source = store.textSource {
                Text("做法来自 \(source.name)（\(source.license)），用量和步骤以原文为准。")
            }
            if let originalURL {
                Link("打开原文", destination: originalURL)
            }
        }
        .font(.footnote)
        .foregroundStyle(.secondary)
    }

    /// 正文是行内 Markdown（加粗、外链），保留列表项里的换行
    private func markdown(_ string: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        return (try? AttributedString(markdown: string, options: options)) ?? AttributedString(string)
    }
}

/// 配图在正文里的滚动定位。单独一个类型，免得和列表项（也按序号区分）的 id 撞上
private struct StepAnchor: Hashable {
    let id: String
}

/// 每次点击都有新的请求身份，同一个目标也能重新高亮。
private struct StepLocation: Equatable {
    let id: String
    private let request = UUID()

    init(id: String) { self.id = id }
}

private struct PhotoAnchor: Hashable {
    let id: Int
}

/// 正文里的一张配图：加载前按原图比例占位，不让正文跳动；点一下看大图；加载失败就收起，不影响看文字
private struct RecipeTextImage: View {
    let photo: RecipeTextPhoto
    let transition: Namespace.ID
    let open: () -> Void
    @State private var image: UIImage?
    @State private var failed = false

    private let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)

    init(photo: RecipeTextPhoto, transition: Namespace.ID, open: @escaping () -> Void) {
        self.photo = photo
        self.transition = transition
        self.open = open
        _image = State(initialValue: RecipeImageCache.cached(photo.url))
    }

    var body: some View {
        if !failed {
            VStack(spacing: 6) {
                Button(action: open) {
                    picture
                }
                .buttonStyle(.plain)
                .disabled(image == nil)
                .zoomTransitionSource(id: photo.id, in: transition)
                .accessibilityLabel(photo.accessibilityLabel)
                .accessibilityHint("查看大图")
                .accessibilityAddTraits(.isImage)

                if let caption = photo.caption {
                    Text(caption)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            // 竖图不占满一屏：限制宽度，让图高不超过 480
            .frame(maxWidth: 480 * photo.aspectRatio)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
            .task(id: photo.url) {
                guard image == nil else { return }
                do {
                    let loaded = try await RecipeImageCache.load(photo.url)
                    withAnimation(.easeOut(duration: 0.2)) {
                        image = loaded
                    }
                } catch {
                    if !Task.isCancelled {
                        failed = true
                    }
                }
            }
        }
    }

    private var picture: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .transition(.opacity)
            } else {
                Rectangle()
                    .fill(.quaternary)
                ProgressView()
            }
        }
        .aspectRatio(photo.aspectRatio, contentMode: .fit)
        .clipShape(shape)
        // 白底的图在浅色模式下和背景分不开，描一圈细边
        .overlay {
            shape.strokeBorder(Color(uiColor: .separator).opacity(0.5), lineWidth: 0.5)
        }
        .contentShape(shape)
    }
}
