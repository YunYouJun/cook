import XCTest
@testable import Cook

final class KitchenWorkbenchLayoutTests: XCTestCase {
    func testUnfoldedPortraitKeepsBothColumnsUsable() {
        for size in [CGSize(width: 669, height: 870), CGSize(width: 641, height: 520)] {
            let frames = KitchenWorkbenchFrames.resolve(size: size, tabletop: false, accessibility: false)
            XCTAssertEqual(frames.mode, .columns)
            XCTAssertGreaterThanOrEqual(frames.recipe.width, 320)
            XCTAssertGreaterThanOrEqual(frames.preparation.width, 320)
            XCTAssertLessThan(frames.recipe.maxX, frames.preparation.minX)
            let bounds = CGRect(origin: .zero, size: size)
            XCTAssertTrue(bounds.contains(frames.recipe))
            XCTAssertTrue(bounds.contains(frames.preparation))
        }
    }

    func testNarrowOrShortWindowAndAccessibleTextKeepSinglePane() {
        for size in [CGSize(width: 640, height: 870), CGSize(width: 669, height: 400)] {
            let frames = KitchenWorkbenchFrames.resolve(size: size, tabletop: false, accessibility: false)
            XCTAssertEqual(frames.mode, .compact)
        }
        let accessible = KitchenWorkbenchFrames.resolve(size: CGSize(width: 669, height: 870), tabletop: false, accessibility: true)
        XCTAssertEqual(accessible.mode, .compact)
    }

    func testTabletopUsesOffCenterSystemDivision() {
        let size = CGSize(width: 720, height: 800)
        let hinge = CGRect(x: -10, y: 352, width: 740, height: 32)
        let frames = KitchenWorkbenchFrames.resolve(size: size, division: hinge, tabletop: false, accessibility: false)
        XCTAssertEqual(frames.mode, .tabletop)
        XCTAssertEqual(frames.recipe.maxY, 352)
        XCTAssertEqual(frames.preparation.minY, 384)
        assertUsableRegions(frames, avoiding: hinge)
    }

    func testBookPoseRespectsVerticalFoldEvenWithManualTabletopAndLargeText() {
        let size = CGSize(width: 900, height: 700)
        let hinge = CGRect(x: 435, y: -50, width: 30, height: 800)
        let frames = KitchenWorkbenchFrames.resolve(size: size, division: hinge, tabletop: true, accessibility: true)
        XCTAssertEqual(frames.mode, .columns)
        assertUsableRegions(frames, avoiding: hinge)
    }

    func testManualTabletopAndSmallWindowFallback() {
        let full = KitchenWorkbenchFrames.resolve(size: CGSize(width: 393, height: 700), tabletop: true, accessibility: false)
        XCTAssertEqual(full.mode, .tabletop)
        XCTAssertGreaterThan(full.preparation.height, 240)
        XCTAssertLessThan(full.recipe.maxY, full.preparation.minY)
        let small = KitchenWorkbenchFrames.resolve(size: CGSize(width: 393, height: 320), tabletop: true, accessibility: false)
        XCTAssertEqual(small.mode, .compact)
        XCTAssertEqual(small.recipe, small.preparation)
        let accessible = KitchenWorkbenchFrames.resolve(size: CGSize(width: 900, height: 700), tabletop: true, accessibility: true)
        XCTAssertEqual(accessible.mode, .compact)
    }

    func testOutsideOrNonDividingRegionsDoNotHideAPane() {
        let size = CGSize(width: 900, height: 700)
        for region in [CGRect(x: 20, y: 30, width: 40, height: 20),
                       CGRect(x: 0, y: 800, width: 900, height: 20),
                       CGRect(x: 0, y: -20, width: 900, height: 40)] {
            let frames = KitchenWorkbenchFrames.resolve(size: size, division: region, tabletop: false, accessibility: false)
            XCTAssertEqual(frames.mode, .columns)
            XCTAssertGreaterThan(frames.recipe.width, 400)
            XCTAssertGreaterThanOrEqual(frames.preparation.width, 320)
        }
    }

    private func assertUsableRegions(_ frames: KitchenWorkbenchFrames, avoiding hinge: CGRect, file: StaticString = #filePath, line: UInt = #line) {
        let bounds = CGRect(origin: .zero, size: frames.size)
        for frame in [frames.recipe, frames.preparation] {
            XCTAssertTrue(bounds.contains(frame), file: file, line: line)
            XCTAssertFalse(frame.intersection(hinge).width > 0 && frame.intersection(hinge).height > 0, file: file, line: line)
            XCTAssertGreaterThan(frame.width, 0, file: file, line: line)
            XCTAssertGreaterThan(frame.height, 0, file: file, line: line)
        }
    }
}
