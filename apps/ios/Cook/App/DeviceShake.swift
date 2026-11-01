import UIKit

extension Notification.Name {
    /// Shared by the visible find-recipes view or decision sheet.
    static let deviceDidShake = Notification.Name("fun.yunle.cook.deviceDidShake")
}

extension UIWindow {
    // SwiftUI 没有摇一摇的回调，从窗口的响应链转出来。
    override open func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        super.motionEnded(motion, with: event)
        if motion == .motionShake {
            NotificationCenter.default.post(name: .deviceDidShake, object: nil)
        }
    }
}
