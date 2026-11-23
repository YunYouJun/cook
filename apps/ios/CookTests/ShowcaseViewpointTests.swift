import XCTest
@testable import Cook

final class ShowcaseViewpointTests: XCTestCase {
    func testBrowsingKeepsEachLearnedDishesViewpointAndClearsRemovedSelections() {
        let custard = UUID()
        let cucumber = UUID()
        let custardView = ShowcaseViewpoint.initial.adjusted(rotation: 30).adjusted(pinch: ShowcaseViewpoint.zoomStep)
        let cucumberView = ShowcaseViewpoint.initial.adjusted(rotation: -30)
        var browsing = ShowcaseBrowsingState()
        browsing.presentation = .showcase
        browsing.featuredDishID = custard
        browsing[viewpoint: custard] = custardView
        XCTAssertEqual(browsing[viewpoint: cucumber], .initial)
        browsing.featuredDishID = cucumber
        browsing[viewpoint: cucumber] = cucumberView
        browsing.presentation = .badges
        XCTAssertEqual(browsing[viewpoint: custard], custardView)
        XCTAssertEqual(browsing[viewpoint: cucumber], cucumberView)
        XCTAssertEqual(browsing.featuredDishID, cucumber)

        browsing.retainLearnedRecords([custard])
        XCTAssertNil(browsing.featuredDishID)
        XCTAssertEqual(browsing[viewpoint: cucumber], .initial)
        XCTAssertEqual(browsing[viewpoint: custard], custardView)
    }

    func testPinchingKeepsRotationAndTurningKeepsMagnification() {
        let turned = ShowcaseViewpoint.initial.adjusted(rotation: -30)
        XCTAssertEqual(turned.angleDegrees, 330)
        let zoomed = turned.adjusted(rotation: 60, pinch: ShowcaseViewpoint.zoomStep)
        XCTAssertEqual(zoomed.angleDegrees, 330)
        XCTAssertEqual(zoomed.zoomPercent, 125)
        let turnedAgain = zoomed.adjusted(rotation: 60)
        XCTAssertEqual(turnedAgain.angleDegrees, 30)
        XCTAssertEqual(turnedAgain.magnification, zoomed.magnification)
    }

    func testPinchAndButtonZoomShareBoundsAndIgnoreInvalidInput() {
        let initial = ShowcaseViewpoint.initial
        let zoomedIn = initial.adjusted(pinch: 10)
        let zoomedOut = initial.adjusted(pinch: 0.01)
        XCTAssertEqual(zoomedIn.zoomPercent, 220)
        XCTAssertEqual(zoomedOut.zoomPercent, 65)
        XCTAssertEqual(zoomedIn.adjusted(pinch: ShowcaseViewpoint.zoomStep), zoomedIn)
        XCTAssertEqual(zoomedOut.adjusted(pinch: 1 / ShowcaseViewpoint.zoomStep), zoomedOut)
        XCTAssertEqual(initial.adjusted(rotation: .infinity), initial)
        for invalid in [Double.nan, .infinity, 0, -1] {
            XCTAssertEqual(initial.adjusted(rotation: 45, pinch: invalid), initial)
        }
    }
}
