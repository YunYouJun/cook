import Foundation
import ImageIO
import Vision

enum FridgePhotoRecognition {
    enum Failure: LocalizedError {
        case unreadableImage
        var errorDescription: String? { "无法读取这张照片，请换一张清晰的照片或手动添加。" }
    }

    /// 照片仅在内存中处理；缩小并按 EXIF 旋转，避免把整张高像素照片交给识别请求。
    static func recognize(_ data: Data, knownNames: [String]) async throws -> FridgeLabelScan {
        let work = Task.detached(priority: .userInitiated) {
            try Task.checkCancellation()
            guard data.count <= 30 * 1024 * 1024,
                let source = CGImageSourceCreateWithData(data as CFData, nil),
                let image = CGImageSourceCreateThumbnailAtIndex(
                    source, 0,
                    [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: 2400,
                    ] as CFDictionary)
            else { throw Failure.unreadableImage }
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.recognitionLanguages = ["zh-Hans", "en-US"]
            request.usesLanguageCorrection = false
            let handler = VNImageRequestHandler(cgImage: image, options: [:])
            try handler.perform([request])
            try Task.checkCancellation()
            let text = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
            let matches = knownNames.filter { !$0.isEmpty && text.contains($0) }
                .sorted { $0.count == $1.count ? $0 < $1 : $0.count > $1.count }
            // 「日本豆腐」不再同时建议「豆腐」。配料表命中多个名称时交给用户选择。
            var names: [String] = []
            for name in matches where !names.contains(where: { $0.contains(name) }) {
                names.append(name)
            }
            if names.isEmpty {
                let classification = VNClassifyImageRequest()
                try? handler.perform([classification])
                names = imageNames((classification.results ?? []).map { ($0.identifier, $0.confidence) })
            }
            try Task.checkCancellation()
            return FridgeLabelParser.parse(text, names: Array(names.prefix(6)))
        }
        return try await withTaskCancellationHandler {
            try await work.value
        } onCancel: {
            work.cancel()
        }
    }

    /// 仅映射 Vision 的具体类别；不把 fruit、vegetable 这样的泛类猜成某一种食材。
    static func imageNames(_ classifications: [(String, Float)]) -> [String] {
        let names = [
            "apple": "苹果", "banana": "香蕉", "bell_pepper": "青椒", "blueberry": "蓝莓",
            "blackberry": "黑莓", "broccoli": "西兰花", "carrot": "胡萝卜", "cucumber": "黄瓜",
            "eggplant": "茄子", "grape": "葡萄", "lettuce": "生菜", "mushroom": "菌菇",
            "onion": "洋葱", "oranges": "橙子", "peach": "桃", "pear": "梨", "pineapple": "菠萝",
            "potato": "土豆", "raspberry": "树莓", "strawberry": "草莓", "tomato": "番茄", "watermelon": "西瓜",
        ]
        var result: [String] = []
        for (identifier, confidence) in classifications.sorted(by: { $0.1 > $1.1 }) where confidence >= 0.35 {
            if let name = names[identifier], !result.contains(name) { result.append(name) }
        }
        return Array(result.prefix(3))
    }
}
