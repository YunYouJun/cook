import SwiftUI
import UIKit
import XCTest
@testable import Cook

@MainActor
final class YunlefunAccountLayoutTests: XCTestCase {
    func testLoginAndCancellationKeepFollowingSectionInPlace() async throws {
        try await checkLoginLayout(dynamicTypeSize: .large)
    }

    func testLoginAndCancellationKeepFollowingSectionInPlaceWithAccessibleText() async throws {
        try await checkLoginLayout(dynamicTypeSize: .accessibility3)
    }

    private func checkLoginLayout(dynamicTypeSize: DynamicTypeSize) async throws {
        guard #available(iOS 17.4, *) else {
            throw XCTSkip("System account sign-in requires iOS 17.4")
        }
        let authorization = SuspendedAccountAuthorization()
        let accounts = YunlefunAccountStore(vault: MemoryYunlefunAccountVault(), authorization: authorization)
        let probe = AccountLayoutProbe()
        let host = UIHostingController(rootView: AccountLayoutHarness(probe: probe)
            .environment(accounts)
            .environment(\.dynamicTypeSize, dynamicTypeSize)
            .frame(width: 320))
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = try XCTUnwrap(scenes.first { $0.activationState == .foregroundActive } ?? scenes.first,
                                 "The layout fixture needs an application window scene")
        let previousKeyWindow = scene.windows.first(where: \.isKeyWindow)
        let window = UIWindow(windowScene: scene)
        window.frame = scene.coordinateSpace.bounds
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.setNeedsLayout()
        host.view.layoutIfNeeded()
        var signInTask: Task<Void, Never>?
        defer {
            signInTask?.cancel()
            authorization.cancel()
            window.isHidden = true
            window.rootViewController = nil
            previousKeyWindow?.makeKey()
        }

        let idle = try await settledMarker(probe, window: window, isBusy: false)
        signInTask = Task { await accounts.signIn() }
        let signingIn = try await settledMarker(probe, window: window, isBusy: true)
        XCTAssertTrue(authorization.isWaiting, "The layout must be measured while authorization is suspended")
        XCTAssertEqual(signingIn.minY, idle.minY, accuracy: 0.5,
                       "Starting login must not push down the following settings section")

        authorization.cancel()
        await signInTask?.value
        let cancelled = try await settledMarker(probe, window: window, isBusy: false)
        XCTAssertFalse(accounts.isBusy)
        XCTAssertNil(accounts.message, "Cancelling authorization is not a login error")
        XCTAssertEqual(cancelled.minY, idle.minY, accuracy: 0.5,
                       "Cancelling login must keep the following settings section in place")
    }

    /// Wait for the requested state and several unchanged layout passes. The
    /// state is part of the preference so an unchanged frame still reports it.
    private func settledMarker(_ probe: AccountLayoutProbe, window: UIWindow, isBusy: Bool) async throws -> CGRect {
        var previous: CGRect?
        var stableSamples = 0
        for _ in 0 ..< 150 {
            if let measurement = probe.latest, measurement.isBusy == isBusy,
               !measurement.frame.isEmpty {
                stableSamples = measurement.frame == previous ? stableSamples + 1 : 0
                previous = measurement.frame
                if stableSamples >= 10 { return measurement.frame }
            } else {
                previous = nil
                stableSamples = 0
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        let attachment = XCTAttachment(image: UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: false)
        })
        attachment.name = "Account layout timeout, isBusy=\(isBusy)"
        attachment.lifetime = .keepAlways
        add(attachment)
        XCTFail("Account layout did not settle for isBusy=\(isBusy); latest=\(String(describing: probe.latest))")
        throw AccountLayoutTimeout()
    }
}

private struct AccountLayoutTimeout: Error {}

@MainActor
private final class SuspendedAccountAuthorization: YunlefunAuthorizing {
    private var continuation: CheckedContinuation<URL, Error>?
    var isWaiting: Bool { continuation != nil }

    func authorize(_ transaction: YunlefunSSO) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation = $0 }
    }

    func cancel() {
        let pending = continuation
        continuation = nil
        pending?.resume(throwing: CancellationError())
    }
}

private struct AccountMarkerMeasurement: Equatable {
    let frame: CGRect
    let isBusy: Bool
}

private struct AccountMarkerPreference: PreferenceKey {
    static var defaultValue: AccountMarkerMeasurement? { nil }

    static func reduce(value: inout AccountMarkerMeasurement?, nextValue: () -> AccountMarkerMeasurement?) {
        if let next = nextValue() { value = next }
    }
}

@MainActor
private final class AccountLayoutProbe {
    var latest: AccountMarkerMeasurement?
}

@MainActor
private struct AccountLayoutHarness: View {
    @Environment(YunlefunAccountStore.self) private var accounts
    let probe: AccountLayoutProbe

    var body: some View {
        List {
            YunlefunAccountSection()
            Section {
                Text("我的菜谱")
                    .background {
                        GeometryReader { geometry in
                            Color.clear.preference(key: AccountMarkerPreference.self,
                                value: AccountMarkerMeasurement(frame: geometry.frame(in: .global),
                                                                isBusy: accounts.isBusy))
                        }
                    }
                    // A List row has its own hosting boundary. Observe inside
                    // that row so the preference is not swallowed by List.
                    .onPreferenceChange(AccountMarkerPreference.self) { probe.latest = $0 }
            }
        }
        .listStyle(.insetGrouped)
    }
}
