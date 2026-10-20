import CookCore
import SwiftUI

/// 图文做法：读 App 内置的正文离线显示，配图从对象存储加载、点开看大图，右上角可以打开原文
struct RecipeTextView: View {
    @Environment(RecipeStore.self) private var store
    let recipe: Recipe
    let text: RecipeText
    @State private var showsPhotos = false
    @State private var photoSelection = 0
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
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(text.blocks.enumerated()), id: \.offset) { offset, block in
                        blockView(block, at: offset)
                    }
                    footer
                        .padding(.top, 12)
                }
                .textSelection(.enabled)
                .padding()
                // iPad 上行太长不好读
                .frame(maxWidth: 680, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            // 大图里翻到别的图时，正文跟着滚过去，收起时缩回那张图的位置
            .onChange(of: photoSelection) { _, id in
                if showsPhotos {
                    proxy.scrollTo(PhotoAnchor(id: id), anchor: .center)
                }
            }
        }
        .fullScreenCover(isPresented: $showsPhotos) {
            RecipeImageViewer(photos: photos, selection: $photoSelection)
                .zoomTransition(sourceID: photoSelection, in: photoTransition)
        }
        .navigationTitle(recipe.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if let originalURL {
                    Link(destination: originalURL) {
                        Label("打开原文", systemImage: "safari")
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func blockView(_ block: RecipeTextBlock, at offset: Int) -> some View {
        switch block {
        case let .heading(heading):
            Text(markdown(heading))
                .font(.title3.bold())
                .padding(.top, 8)
                .accessibilityAddTraits(.isHeader)
        case let .paragraph(paragraph):
            if !paragraph.isEmpty {
                Text(markdown(paragraph))
            }
        case let .list(ordered, start, items):
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
        let caption = displayCaption(alt)
        return RecipeTextPhoto(
            id: offset,
            url: url,
            aspectRatio: CGFloat(width) / CGFloat(max(height, 1)),
            caption: caption,
            accessibilityLabel: caption ?? "\(recipe.name)的配图"
        )
    }

    /// 原文的图片说明。「XX-预览图-1」「红烧肉成品」这种只是文件名或菜名，英文的多是图片来源，都不显示
    private func displayCaption(_ alt: String?) -> String? {
        guard let alt = alt?.trimmingCharacters(in: .whitespaces), !alt.isEmpty else { return nil }
        let isChinese = alt.unicodeScalars.contains { (0x4E00 ... 0x9FFF).contains($0.value) }
        guard isChinese, !alt.contains("预览图"), !alt.hasSuffix("成品"), !alt.contains(recipe.name) else { return nil }
        return alt
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
