import AVFoundation
import XCTest
@testable import Cook

final class LocalRecipeVideoStoreTests: XCTestCase {
    func testPlayableImportAndInvalidReplacement() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalRecipeVideoStore(root: directory)
        let sample = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "local-video", withExtension: "mp4"))
        let saved = try await store.save(id: "playable", recipeName: "验收", source: sample)
        let duration = try await AVURLAsset(url: saved.url).load(.duration)
        XCTAssertGreaterThan(duration.seconds, 0)
        XCTAssertEqual(try Data(contentsOf: saved.url), try Data(contentsOf: sample))
        let invalid = directory.appendingPathComponent("invalid.mp4")
        try Data("invalid movie".utf8).write(to: invalid)
        do { _ = try await store.save(id: "playable", recipeName: "invalid", source: invalid); XCTFail("invalid movie accepted") } catch {}
        let retained = try await store.video(for: "playable")
        XCTAssertEqual(retained?.url, saved.url)
    }

    func testAtomicReplacementSafePathsAndOrphanAccess() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let source = directory.appendingPathComponent("source.mp4")
        try Data("test-video".utf8).write(to: source)
        let root = directory.appendingPathComponent("videos")
        let store = LocalRecipeVideoStore(root: root)
        let id = "../../private/id"
        let first = try await store.copyValidated(id: id, recipeName: "私人菜谱", source: source)
        XCTAssertTrue(first.url.path.hasPrefix(root.path + "/"))
        let failing = LocalRecipeVideoStore(root: root, writeManifest: { _, _ in throw CocoaError(.fileWriteOutOfSpace) })
        do { _ = try await failing.copyValidated(id: id, recipeName: "新名", source: source); XCTFail("must fail") } catch {}
        let after = try await store.video(for: id)
        XCTAssertEqual(after?.url, first.url)
        XCTAssertEqual(try Data(contentsOf: first.url), Data("test-video".utf8))
        let all = try await store.all()
        XCTAssertEqual(all.map(\.metadata.recipeName), ["私人菜谱"])
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: first.url.deletingLastPathComponent().path).count, 2)
        try await store.remove(id: id)
        let removed = try await store.all()
        XCTAssertTrue(removed.isEmpty)
    }
}
