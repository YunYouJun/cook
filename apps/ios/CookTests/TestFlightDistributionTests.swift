import StoreKit
import XCTest
@testable import Cook

@MainActor
final class TestFlightDistributionTests: XCTestCase {
    func testVerifiedSandboxDistributionShowsGuideBeforeAnyReceiptExists() {
        XCTAssertTrue(TestFlightDistribution.shouldShowGuide(environment: .sandbox, receiptName: nil))
    }

    func testVerifiedProductionAndXcodeNeverUseASandboxReceiptFallback() {
        XCTAssertFalse(TestFlightDistribution.shouldShowGuide(environment: .production, receiptName: "sandboxReceipt"))
        XCTAssertFalse(TestFlightDistribution.shouldShowGuide(environment: .xcode, receiptName: "sandboxReceipt"))
    }

    func testLocalBuildsCannotShowTheGuideEvenWithSandboxEvidence() {
        XCTAssertFalse(TestFlightDistribution.shouldShowGuide(environment: .sandbox, receiptName: "sandboxReceipt", hasEmbeddedProfile: true))
        XCTAssertFalse(TestFlightDistribution.shouldShowGuide(environment: .sandbox, receiptName: "sandboxReceipt", isDebug: true))
        XCTAssertFalse(TestFlightDistribution.shouldShowGuide(environment: .sandbox, receiptName: "sandboxReceipt", isSimulator: true))
    }

    func testReceiptFallbackRequiresASandboxReceipt() {
        XCTAssertTrue(TestFlightDistribution.shouldShowGuide(environment: nil, receiptName: "sandboxReceipt"))
        XCTAssertFalse(TestFlightDistribution.shouldShowGuide(environment: nil, receiptName: "receipt"))
        XCTAssertFalse(TestFlightDistribution.shouldShowGuide(environment: nil, receiptName: nil))
    }
}
