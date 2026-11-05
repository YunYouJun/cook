import AVKit
import CookCore
import SwiftUI

private struct PrivateRecipeVideoStoreKey: EnvironmentKey {
    static let defaultValue = PrivateRecipeVideoStore.shared
}

extension EnvironmentValues {
    var privateRecipeVideos: PrivateRecipeVideoStore {
        get { self[PrivateRecipeVideoStoreKey.self] }
        set { self[PrivateRecipeVideoStoreKey.self] = newValue }
    }
}

struct PrivateRecipeVideoView: View {
    let video: PrivateRecipeVideo
    @Environment(\.privateRecipeVideos) private var files
    @State private var retry = 0
    @State private var url: URL?
    @State private var error: String?

    var body: some View {
        Group {
            if let url {
                PrivateVideoPlayer(url: url) { error = "视频播放失败，请重试或重新选择视频。"; self.url = nil }
                ShareLink(item: url) { Label("导出视频文件", systemImage: "square.and.arrow.up") }
            } else if let error {
                Text(error).font(.callout).foregroundStyle(.secondary)
                Button("重试加载视频") { retry += 1 }
            } else {
                ProgressView(video.location == .iCloud ? "正在读取或下载视频…" : "正在读取视频…")
            }
            Text(video.originalName).font(.caption).foregroundStyle(.secondary)
            Text(video.location == .iCloud ? "存于 iCloud Drive；下载后可离线观看。" : "仅存于原设备，可在编辑中存入 iCloud Drive。")
                .font(.caption).foregroundStyle(.secondary)
        }
        .task(id: "\(video.id)-\(retry)") {
            url = nil
            error = nil
            do { url = try await files.playableURL(video) }
            catch is CancellationError { }
            catch { self.error = error.localizedDescription }
        }
    }
}

private struct PrivateVideoPlayer: View {
    @State private var player: AVPlayer
    let failed: () -> Void

    init(url: URL, failed: @escaping () -> Void) {
        _player = State(initialValue: AVPlayer(url: url))
        self.failed = failed
    }

    var body: some View {
        VideoPlayer(player: player)
            .frame(minHeight: 220, idealHeight: 280)
            .accessibilityIdentifier("private-recipe-video-player")
            .onReceive(player.currentItem!.publisher(for: \.status)) { status in
                if status == .failed { failed() }
            }
            .onDisappear { player.pause() }
    }
}
