import SwiftUI
import UIKit
import XCTest
@testable import Cook

@MainActor
final class CookPresentationToggleTests: XCTestCase {
    func testUnrelatedRefreshKeepsExistingMenuAndActions() throws {
        let coordinator = CookPresentationToggle.Coordinator(selection: .constant(.list))
        let button = UIButton(type: .system)
        coordinator.updateMenu(on: button, selection: .list, usesCurrentSubtitle: true)
        let original = try XCTUnwrap(button.menu)
        let action = try XCTUnwrap(original.children.first as? UIAction)

        coordinator.updateMenu(on: button, selection: .list, usesCurrentSubtitle: true)

        XCTAssertTrue(button.menu === original, "Unrelated SwiftUI refreshes must not replace a menu that UIKit may be presenting")
        XCTAssertTrue(button.menu?.children.first === action)
    }

    func testSelectionAndTextSizeUpdateCurrentOption() throws {
        let coordinator = CookPresentationToggle.Coordinator(selection: .constant(.list))
        let button = UIButton(type: .system)
        coordinator.updateMenu(on: button, selection: .list, usesCurrentSubtitle: false)
        let initial = try XCTUnwrap(button.menu)
        XCTAssertEqual((initial.children[0] as? UIAction)?.state, .on)
        XCTAssertEqual((initial.children[1] as? UIAction)?.state, .off)

        coordinator.updateMenu(on: button, selection: .kitchen, usesCurrentSubtitle: false)
        let kitchen = try XCTUnwrap(button.menu)
        XCTAssertFalse(kitchen === initial)
        XCTAssertEqual((kitchen.children[0] as? UIAction)?.state, .off)
        XCTAssertEqual((kitchen.children[1] as? UIAction)?.state, .on)

        coordinator.updateMenu(on: button, selection: .kitchen, usesCurrentSubtitle: true)
        let accessible = try XCTUnwrap(button.menu)
        XCTAssertFalse(accessible === kitchen)
        XCTAssertNil((accessible.children[0] as? UIAction)?.subtitle)
        XCTAssertEqual((accessible.children[1] as? UIAction)?.subtitle, "当前视图")
        XCTAssertEqual((accessible.children[1] as? UIAction)?.state, .off)
    }
}
