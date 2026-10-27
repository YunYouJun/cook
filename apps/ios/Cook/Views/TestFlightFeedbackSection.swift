import SwiftUI

/// A short beta-only entry to the shared instructions; regular feedback remains available below.
struct TestFlightFeedbackSection: View {
    let appName: String
    let isTestFlight: Bool

    var body: some View {
        Group {
            if isTestFlight {
                Section {
                    Text("使用测试版时截屏，打开系统截屏预览，选择“共享 Beta 版反馈”，填写问题说明后提交。")
                        .font(.subheadline)
                    Text("也可以打开 TestFlight，选择\(appName)，点“发送反馈”，再选择“包括截屏”。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    Link("查看截图反馈指引", destination: URL(string: "https://support.yunle.fun/testflight")!)
                        .accessibilityIdentifier("feedback.testflight.guide")
                    Link("Apple 官方 TestFlight 指引", destination: URL(string: "https://testflight.apple.com/")!)
                        .accessibilityIdentifier("feedback.testflight.official")
                } header: {
                    Text("TestFlight 截图反馈")
                } footer: {
                    Text("通过 TestFlight 提交的反馈会发送给 Apple 和开发者。提交前请遮挡截图中的个人信息。")
                }
                .accessibilityIdentifier("feedback.testflight.section")
            }
        }
    }
}

#Preview("TestFlight") {
    Form {
        TestFlightFeedbackSection(appName: "云食册", isTestFlight: true)
    }
}

#Preview("App Store") {
    Form {
        TestFlightFeedbackSection(appName: "云食册", isTestFlight: false)
        Section("意见反馈") { Text("原有反馈入口") }
    }
}
