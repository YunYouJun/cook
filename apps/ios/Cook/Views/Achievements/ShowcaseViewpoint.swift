import Foundation

/// The browsing state belongs to the current cooking journal, across its destinations.
struct ShowcaseBrowsingState {
    enum Presentation {
        case badges, showcase
    }

    var presentation = Presentation.badges
    var featuredDishID: UUID?
    private var viewpoints: [UUID: ShowcaseViewpoint] = [:]

    subscript(viewpoint id: UUID) -> ShowcaseViewpoint {
        get { viewpoints[id, default: .initial] }
        set { viewpoints[id] = newValue }
    }

    mutating func retainLearnedRecords(_ ids: Set<UUID>) {
        if let featuredDishID, !ids.contains(featuredDishID) {
            self.featuredDishID = nil
        }
        viewpoints = viewpoints.filter { ids.contains($0.key) }
    }
}

struct ShowcaseViewpoint: Equatable {
    static let initial = Self()
    static let minimumMagnification = 0.65
    static let maximumMagnification = 2.2
    static let zoomStep = 1.25

    private(set) var angle = 0.0
    private(set) var magnification = 1.0

    var angleDegrees: Int { Int(angle.rounded()) % 360 }
    var zoomPercent: Int { Int((magnification * 100).rounded()) }

    func adjusted(rotation: Double = 0, pinch: Double? = nil) -> Self {
        var next = self
        if let pinch {
            guard pinch.isFinite, pinch > 0 else { return self }
            next.magnification = min(Self.maximumMagnification, max(Self.minimumMagnification, magnification * pinch))
        } else {
            let sum = angle + rotation
            guard sum.isFinite else { return self }
            let remainder = sum.truncatingRemainder(dividingBy: 360)
            let wrapped = remainder < 0 ? remainder + 360 : remainder
            next.angle = wrapped >= 360 ? 0 : wrapped
        }
        return next
    }
}
