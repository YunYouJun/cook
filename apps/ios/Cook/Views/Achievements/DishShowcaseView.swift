import SwiftUI

struct DishShowcaseView: View {
    let name: String
    let learnedAt: Date
    let assets: ShowcaseAssetStore
    @Environment(\.dismiss) private var dismiss
    @State private var photo: (url: URL, image: UIImage)?
    @State private var showingModel = false

    private var imageURL: URL? { assets.installed[.image]?.url }
    private var modelURL: URL? { assets.installed[.model]?.url }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    hero
                    VStack(alignment: .leading, spacing: 12) {
                        Text("离线素材").font(.headline)
                            .accessibilityAddTraits(.isHeader)
                        ForEach(assets.manifest.packs, id: \.kind) { pack in
                            ShowcasePackRow(pack: pack, assets: assets, openModel: { showingModel = true })
                        }
                    }
                }
                .frame(maxWidth: 480)
                .padding(20)
                .frame(maxWidth: .infinity)
            }
            .accessibilityIdentifier("showcase.content")
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("菜肴成就")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .navigationDestination(isPresented: $showingModel) {
                if let modelURL {
                    ShowcaseModelView(recipeID: assets.manifest.recipeID, name: name, modelURL: modelURL)
                        .id(modelURL)
                }
            }
        }
        .task { assets.reload() }
        .task(id: imageURL) {
            if let imageURL, let image = UIImage(contentsOfFile: imageURL.path) {
                photo = (imageURL, image)
            } else {
                photo = nil
            }
        }
        .onChange(of: modelURL) { _, url in
            if url == nil { showingModel = false }
        }
    }

    private var hero: some View {
        VStack(spacing: 8) {
            Group {
                if let photo, photo.url == imageURL {
                    Image(uiImage: photo.image)
                        .resizable()
                        .scaledToFit()
                        .accessibilityLabel("\(name)配图")
                        .accessibilityIdentifier("showcase.preview.image")
                } else {
                    ZStack {
                        LearnedDishBadge(catalogRecipeID: assets.manifest.recipeID, size: 116)
                    }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("\(name)徽章")
                        .accessibilityIdentifier("showcase.preview.badge")
                }
            }
            .frame(maxWidth: 300, maxHeight: 180)
            Text(name).font(.title2.bold())
            Text("首次学会 \(CookingDates.plaque(learnedAt))")
                .font(.footnote.monospacedDigit())
                .foregroundStyle(CookingJournalPalette.warmInk)
                .accessibilityLabel("首次学会 \(CookingDates.celebration(learnedAt))")
                .accessibilityIdentifier("showcase.learnedAt")
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .multilineTextAlignment(.center)
    }
}

private struct ShowcasePackRow: View {
    let pack: ShowcaseAsset
    let assets: ShowcaseAssetStore
    let openModel: () -> Void

    private var name: String { pack.kind == .image ? "图片包" : "模型包" }
    private var symbol: String { pack.kind == .image ? "photo" : "cube" }
    private var installation: ShowcaseAssetStore.Installation? { assets.installed[pack.kind] }
    private var hasUpdate: Bool {
        if let installation { return installation.url.lastPathComponent != pack.filename }
        return false
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(name, systemImage: symbol).font(.subheadline.weight(.semibold))
            if let installation {
                Text("已下载 · \(formattedSize(installation.bytes))")
                    .accessibilityIdentifier("showcase.\(pack.kind.rawValue).installed")
            } else {
                Text(formattedSize(pack.bytes))
            }
            if let fraction = assets.progress[pack.kind] {
                ProgressView(value: fraction) {
                    Text("正在下载\(name)")
                }
                Button("取消下载", role: .cancel) { assets.cancel(pack.kind) }
                    .accessibilityLabel("取消下载\(name)")
                if pack.kind == .model, installation != nil { modelButton }
            } else {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { actions }
                    VStack(alignment: .leading, spacing: 8) { actions }
                }
            }
            if let message = assets.problems[pack.kind] {
                Text(message).foregroundStyle(.secondary)
            }
        }
        .font(.footnote)
        .foregroundStyle(.primary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }

    @ViewBuilder private var actions: some View {
        if installation == nil || hasUpdate {
            Button {
                assets.download(pack.kind)
            } label: {
                Label(hasUpdate ? "更新（\(formattedSize(pack.bytes))）" : "下载", systemImage: "arrow.down.circle")
                    .fixedSize()
            }
            .buttonStyle(.bordered)
            .accessibilityLabel(hasUpdate ? "更新\(name)" : "下载\(name)")
            .accessibilityIdentifier("showcase.\(pack.kind.rawValue).download")
        }
        if installation != nil {
            if pack.kind == .model {
                modelButton
            }
            Button("删除", role: .destructive) { assets.remove(pack.kind) }
                .buttonStyle(.bordered)
                .accessibilityLabel("删除\(name)")
                .accessibilityIdentifier("showcase.\(pack.kind.rawValue).delete")
        }
    }

    private var modelButton: some View {
        Button(action: openModel) {
            Label("查看 3D", systemImage: "rotate.3d").fixedSize()
        }
        .buttonStyle(.bordered)
        .accessibilityIdentifier("showcase.model.open")
    }

    private func formattedSize(_ bytes: Int) -> String {
        bytes.formatted(.byteCount(style: .file))
    }
}
