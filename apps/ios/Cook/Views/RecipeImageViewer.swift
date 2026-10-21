import SwiftUI
import UIKit

/// 图文做法里的一张配图
struct RecipeTextPhoto: Identifiable {
    /// 在正文里是第几块，同时用来滚动定位和做放大转场
    let id: Int
    let url: URL
    let aspectRatio: CGFloat
    /// 显示在图下面的说明；原文里只是文件名或菜名的不显示
    let caption: String?
    let accessibilityLabel: String
}

/// 配图下载后解码好放在内存里，正文和大图共用，点开大图时不用重新加载
enum RecipeImageCache {
    private static let cache: NSCache<NSURL, UIImage> = {
        let cache = NSCache<NSURL, UIImage>()
        cache.totalCostLimit = 120 * 1024 * 1024
        return cache
    }()

    static func cached(_ url: URL) -> UIImage? {
        cache.object(forKey: url as NSURL)
    }

    static func load(_ url: URL) async throws -> UIImage {
        if let image = cached(url) {
            return image
        }
        let (data, response) = try await URLSession.shared.data(from: url)
        if let response = response as? HTTPURLResponse, !(200 ..< 300).contains(response.statusCode) {
            throw URLError(.badServerResponse)
        }
        guard let decoded = UIImage(data: data) else {
            throw URLError(.cannotDecodeContentData)
        }
        // 在后台解码好，滚动和转场时不卡
        let image = await decoded.byPreparingForDisplay() ?? decoded
        let pixels = image.size.width * image.size.height * image.scale * image.scale
        cache.setObject(image, forKey: url as NSURL, cost: Int(pixels) * 4)
        return image
    }
}

/// 全屏看配图：左右滑动切换，双指或双击放大，轻点隐藏按钮，右上角分享或存到相册。
/// iOS 18 起从正文里的那张图放大展开，下滑收回原处
struct RecipeImageViewer: View {
    let photos: [RecipeTextPhoto]
    @Binding var selection: Int
    @Environment(\.dismiss) private var dismiss
    @State private var chromeHidden = false

    private var index: Int? {
        photos.firstIndex { $0.id == selection }
    }

    var body: some View {
        TabView(selection: $selection) {
            ForEach(photos) { photo in
                ZoomablePhoto(photo: photo, isCurrent: photo.id == selection) {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        chromeHidden.toggle()
                    }
                }
                .tag(photo.id)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .ignoresSafeArea()
        .background(Color.black.ignoresSafeArea())
        .overlay(alignment: .top) {
            if !chromeHidden {
                topBar
                    .transition(.opacity)
            }
        }
        .overlay(alignment: .bottom) {
            if !chromeHidden, let index, let caption = photos[index].caption {
                Text(caption)
                    .font(.subheadline)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .floatingGlass(in: Capsule())
                    .padding()
                    .transition(.opacity)
            }
        }
        .environment(\.colorScheme, .dark)
        .statusBarHidden()
        // 隐藏按钮时连 Home 指示条一起淡掉，只留图片
        .persistentSystemOverlays(chromeHidden ? .hidden : .automatic)
        // VoiceOver 双指搓擦（Z 字）关闭
        .accessibilityAction(.escape) {
            dismiss()
        }
    }

    private var topBar: some View {
        HStack {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .floatingGlass(in: Circle(), interactive: true)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("关闭")

            Spacer()

            // 分享或存到相册，和系统相册一样放在右上角
            if let index, let image = RecipeImageCache.cached(photos[index].url) {
                let photo = photos[index]
                ShareLink(
                    item: Image(uiImage: image),
                    preview: SharePreview(photo.caption ?? photo.accessibilityLabel, image: Image(uiImage: image))
                ) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.body.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .floatingGlass(in: Circle(), interactive: true)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("分享")
            }
        }
        .overlay {
            if photos.count > 1, let index {
                Text("\(index + 1) / \(photos.count)")
                    .font(.subheadline.monospacedDigit())
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .floatingGlass(in: Capsule())
                    .accessibilityLabel("第 \(index + 1) 张，共 \(photos.count) 张")
            }
        }
        .foregroundStyle(.white)
        .padding(.horizontal)
        .padding(.top, 8)
    }
}

/// 大图里的一页：先用正文里已经加载好的图，没有再下载
private struct ZoomablePhoto: View {
    let photo: RecipeTextPhoto
    let isCurrent: Bool
    let onTap: () -> Void
    @State private var image: UIImage?
    @State private var failed = false

    init(photo: RecipeTextPhoto, isCurrent: Bool, onTap: @escaping () -> Void) {
        self.photo = photo
        self.isCurrent = isCurrent
        self.onTap = onTap
        _image = State(initialValue: RecipeImageCache.cached(photo.url))
    }

    var body: some View {
        Group {
            if let image {
                ZoomableImageView(image: image, isCurrent: isCurrent, onTap: onTap)
                    .accessibilityElement()
                    .accessibilityLabel(photo.accessibilityLabel)
                    .accessibilityAddTraits(.isImage)
            } else if failed {
                ContentUnavailableView("图片加载失败", systemImage: "photo", description: Text("检查网络后再打开一次。"))
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onTap)
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .contentShape(Rectangle())
                    .onTapGesture(perform: onTap)
            }
        }
        .ignoresSafeArea()
        .task(id: photo.url) {
            guard image == nil else { return }
            do {
                image = try await RecipeImageCache.load(photo.url)
            } catch {
                if !Task.isCancelled {
                    failed = true
                }
            }
        }
    }
}

/// 用 UIScrollView 做缩放：双指缩放、双击放大到点的位置，放大后可以拖着看，图片始终居中
private struct ZoomableImageView: UIViewRepresentable {
    let image: UIImage
    /// 翻到别的图后把这一张缩回原样，翻回来时和系统相册一样从整张图看起
    let isCurrent: Bool
    let onTap: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTap: onTap)
    }

    func makeUIView(context: Context) -> ZoomingScrollView {
        let scrollView = ZoomingScrollView()
        scrollView.delegate = context.coordinator
        scrollView.imageView.image = image

        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)

        let singleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped))
        singleTap.require(toFail: doubleTap)
        scrollView.addGestureRecognizer(singleTap)
        return scrollView
    }

    func updateUIView(_ scrollView: ZoomingScrollView, context: Context) {
        context.coordinator.onTap = onTap
        if scrollView.imageView.image !== image {
            scrollView.imageView.image = image
            scrollView.resetZoom()
        } else if !isCurrent, scrollView.zoomScale != scrollView.minimumZoomScale {
            scrollView.resetZoom()
        }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var onTap: () -> Void

        init(onTap: @escaping () -> Void) {
            self.onTap = onTap
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            (scrollView as? ZoomingScrollView)?.imageView
        }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            (scrollView as? ZoomingScrollView)?.centerImage()
        }

        @objc func tapped() {
            onTap()
        }

        @objc func doubleTapped(_ recognizer: UITapGestureRecognizer) {
            guard let scrollView = recognizer.view as? ZoomingScrollView else { return }
            if scrollView.zoomScale > scrollView.minimumZoomScale {
                scrollView.setZoomScale(scrollView.minimumZoomScale, animated: true)
                return
            }
            let point = recognizer.location(in: scrollView.imageView)
            // 放大到图片铺满屏幕（横图在手机上就是撑满高度），至少放大 2 倍
            let scale = min(scrollView.maximumZoomScale, max(2, scrollView.fillScale))
            let size = CGSize(width: scrollView.bounds.width / scale, height: scrollView.bounds.height / scale)
            let rect = CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height)
            scrollView.zoom(to: rect, animated: true)
        }
    }
}

/// 缩放为 1 时图片按比例放进可见区域；尺寸变了（旋转、分屏）就重新排
private final class ZoomingScrollView: UIScrollView {
    let imageView = UIImageView()
    private var laidOutSize: CGSize = .zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        minimumZoomScale = 1
        maximumZoomScale = 4
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        decelerationRate = .fast
        backgroundColor = .clear
        imageView.contentMode = .scaleAspectFit
        imageView.isAccessibilityElement = false
        addSubview(imageView)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != laidOutSize {
            laidOutSize = bounds.size
            resetZoom()
        }
    }

    func resetZoom() {
        zoomScale = minimumZoomScale
        imageView.frame = CGRect(origin: .zero, size: fittedSize)
        contentSize = imageView.frame.size
        centerImage()
        contentOffset = CGPoint(x: -contentInset.left, y: -contentInset.top)
    }

    private var fittedSize: CGSize {
        guard let size = imageView.image?.size, size.width > 0, size.height > 0, bounds.width > 0, bounds.height > 0 else {
            return bounds.size
        }
        let scale = min(bounds.width / size.width, bounds.height / size.height)
        return CGSize(width: (size.width * scale).rounded(.down), height: (size.height * scale).rounded(.down))
    }

    /// 缩放到这个倍数时图片正好铺满可见区域
    var fillScale: CGFloat {
        let fitted = fittedSize
        guard fitted.width > 0, fitted.height > 0 else { return 1 }
        return max(bounds.width / fitted.width, bounds.height / fitted.height)
    }

    /// 图片比可见区域小的方向用 contentInset 居中
    func centerImage() {
        let horizontal = max(0, (bounds.width - contentSize.width) / 2)
        let vertical = max(0, (bounds.height - contentSize.height) / 2)
        contentInset = UIEdgeInsets(top: vertical, left: horizontal, bottom: vertical, right: horizontal)
    }
}

extension View {
    /// iOS 18 起作为放大转场的起点，旧系统不变
    @ViewBuilder
    func zoomTransitionSource(id: some Hashable, in namespace: Namespace.ID) -> some View {
        if #available(iOS 18.0, *) {
            matchedTransitionSource(id: id, in: namespace)
        } else {
            self
        }
    }

    /// iOS 18 起从起点放大展开、下滑收回；旧系统用默认的全屏弹出
    @ViewBuilder
    func zoomTransition(sourceID: some Hashable, in namespace: Namespace.ID) -> some View {
        if #available(iOS 18.0, *) {
            navigationTransition(.zoom(sourceID: sourceID, in: namespace))
        } else {
            self
        }
    }
}
