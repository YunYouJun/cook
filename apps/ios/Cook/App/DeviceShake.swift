import UIKit

extension Notification.Name {
    /// 摇一摇手机。找菜页在未打开详情时处理：灵感可见则从当前一批决定，否则从全部当前候选决定。
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
