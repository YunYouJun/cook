import AVKit
import SwiftUI
import UniformTypeIdentifiers

struct LocalRecipeVideoSection: View {
    let customId: String
    let recipeName: String
    @State private var attachment: StoredRecipeVideo?
    @State private var player: AVPlayer?
    @State private var importing = false
    @State private var deleting = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        Section {
            if let attachment {
                VideoPlayer(player: player).frame(minHeight: 210)
                Text(attachment.metadata.filename).font(.caption)
                ShareLink("导出视频", item: attachment.url)
                Button("移除视频", role: .destructive) { deleting = true }.disabled(busy)
            } else { Text("这台设备尚未添加视频。").foregroundStyle(.secondary) }
            Button(attachment == nil ? "添加本机视频" : "替换本机视频") { importing = true }.disabled(busy)
            if busy { ProgressView("正在保存视频…") }
            if let error {
                Text(error).foregroundStyle(.red)
                Button("重试读取") { Task { await reload() } }
            }
        } header: { Text("本机视频") } footer: {
            Text("视频只保存在本机，不随 iCloud 或菜谱 JSON 迁移，请单独导出备份。删除菜谱后仍可在「我的菜谱 → 本机视频」导出或移除附件。")
        }
        .task(id: customId) { await reload() }
        .onDisappear { player?.pause() }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.mpeg4Movie, .quickTimeMovie]) { result in
            Task { @MainActor in
                busy = true
                defer { busy = false }
                do {
                    let source = try result.get()
                    let granted = source.startAccessingSecurityScopedResource()
                    defer { if granted { source.stopAccessingSecurityScopedResource() } }
                    _ = try await LocalRecipeVideoStore.shared.save(id: customId, recipeName: recipeName, source: source)
                    await reload()
                } catch { self.error = "视频未能保存，原视频保留。\(error.localizedDescription)" }
            }
        }
        .confirmationDialog("移除本机视频？", isPresented: $deleting, titleVisibility: .visible) {
            Button("移除", role: .destructive) {
                Task { @MainActor in
                    busy = true
                    defer { busy = false }
                    do { try await LocalRecipeVideoStore.shared.remove(id: customId); await reload() }
                    catch { self.error = "视频移除失败，请重试。\(error.localizedDescription)" }
                }
            }
        } message: { Text("其他设备及已导出的文件不受影响。") }
    }
    @MainActor private func reload() async {
        do {
            let video = try await LocalRecipeVideoStore.shared.video(for: customId)
            player?.pause()
            attachment = video
            player = video.map { AVPlayer(url: $0.url) }
            error = nil
        } catch { self.error = error.localizedDescription }
    }
}

struct LocalVideoLibraryView: View {
    @State private var videos: [StoredRecipeVideo] = []
    @State private var error: String?
    var body: some View {
        List {
            Section {
                ForEach(videos, id: \.metadata.id) { video in
                    NavigationLink(video.metadata.recipeName) {
                        List { LocalRecipeVideoSection(customId: video.metadata.customId, recipeName: video.metadata.recipeName) }
                            .navigationTitle(video.metadata.recipeName)
                    }
                }
                if videos.isEmpty { Text("还没有本机视频") }
            } footer: { Text("这里保留所有本机视频，包括已删除菜谱的附件，可随时导出或移除。") }
            if let error { Text(error); Button("重试") { Task { await load() } } }
        }
        .navigationTitle("本机视频")
        .task { await load() }
    }
    @MainActor private func load() async {
        do { videos = try await LocalRecipeVideoStore.shared.all(); error = nil }
        catch { self.error = error.localizedDescription }
    }
}
