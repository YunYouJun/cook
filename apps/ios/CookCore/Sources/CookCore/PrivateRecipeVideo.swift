import Foundation

/// File names and locations are independent of device-specific absolute paths or access bookmarks.
public struct PrivateRecipeVideo: Codable, Equatable, Sendable, Identifiable {
    public enum Location: String, Codable, Sendable { case local, iCloud }
    public static let byteLimit: Int64 = 500 * 1024 * 1024
    public static let extensions: Set<String> = ["mp4", "mov", "m4v"]
    public var id: String
    public var fileExtension: String
    public var originalName: String
    public var byteCount: Int64
    public var location: Location
    public var deviceID: String?
    public var fileName: String { "\(id).\(fileExtension)" }

    public init(id: String = UUID().uuidString.lowercased(), fileExtension: String, originalName: String, byteCount: Int64, location: Location, deviceID: String? = nil) {
        self.id = id
        self.fileExtension = fileExtension
        self.originalName = originalName
        self.byteCount = byteCount
        self.location = location
        self.deviceID = deviceID
    }

    public func validated() throws -> Self {
        guard UUID(uuidString: id)?.uuidString.lowercased() == id,
              Self.extensions.contains(fileExtension), byteCount > 0, byteCount <= Self.byteLimit,
              !originalName.isEmpty, originalName.utf16.count <= 255,
              originalName.rangeOfCharacter(from: .controlCharacters) == nil,
              location == .iCloud ? deviceID == nil : deviceID.flatMap(UUID.init(uuidString:)) != nil
        else { throw PrivateRecipeFileError.invalid }
        return self
    }
}

/// The stored payload retains the v1 recipe envelope so older builds can still read its text.
/// Only the native reader restores the top-level attachment map; v1 export deliberately omits it.
public enum PrivateRecipeNativeFile {
    public static func export(recipes: [PrivateRecipe], backup: Bool = false, exportedAt: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) throws -> String {
        let portable = try PrivateRecipeFile(recipes: recipes, exportedAt: exportedAt).export()
        var object = try JSONSerialization.jsonObject(with: Data(portable.utf8)) as! [String: Any]
        var videos: [String: PrivateRecipeVideo] = [:]
        for recipe in recipes {
            if let video = recipe.video { videos[recipe.customId] = try video.validated() }
        }
        object["videos"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(videos))
        if backup { object["version"] = 2 }
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .prettyPrinted, .withoutEscapingSlashes])
        guard data.count <= PrivateRecipeFile.byteLimit else { throw PrivateRecipeFileError.tooLarge }
        return String(decoding: data, as: UTF8.self)
    }

    public static func parse(_ text: String) throws -> [PrivateRecipe] {
        guard text.utf8.count <= PrivateRecipeFile.byteLimit else { throw PrivateRecipeFileError.tooLarge }
        struct Header: Decodable { let version: Int }
        let version = try JSONDecoder().decode(Header.self, from: Data(text.utf8)).version
        guard var object = try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any],
              [1, 2].contains(version)
        else { throw PrivateRecipeFileError.invalid }
        // Preserve the portable parser's strict validation of every recipe.
        object["version"] = 1
        let portable = try JSONSerialization.data(withJSONObject: object)
        var recipes = try PrivateRecipeFile.parse(String(decoding: portable, as: UTF8.self)).recipes
        if let rawVideos = object["videos"] {
            let fields: Set<String> = ["id", "fileExtension", "originalName", "byteCount", "location", "deviceID"]
            guard let entries = rawVideos as? [String: [String: Any]], entries.values.allSatisfy({ Set($0.keys).isSubset(of: fields) && !$0.values.contains(where: { $0 is NSNull }) }) else { throw PrivateRecipeFileError.invalid }
            let videos = try JSONDecoder().decode([String: PrivateRecipeVideo].self, from: JSONSerialization.data(withJSONObject: rawVideos))
            guard Set(videos.keys).isSubset(of: Set(recipes.map(\.customId))) else { throw PrivateRecipeFileError.invalid }
            for index in recipes.indices {
                recipes[index].video = try videos[recipes[index].customId]?.validated()
            }
        } else if version == 2 { throw PrivateRecipeFileError.invalid }
        return recipes
    }
}
