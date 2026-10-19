import SwiftUI

/// 我的：做菜偏好和关于
struct SettingsView: View {
    @Environment(RecipeStore.self) private var store

    private var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "-"
        let build = info?["CFBundleVersion"] as? String ?? "-"
        return "\(version)（\(build)）"
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    PreferenceRows()
                } header: {
                    Text("做菜偏好")
                } footer: {
                    Text("登录 iCloud 后，收藏和浏览记录会在你的设备间同步。")
                }
                Section("关于") {
                    LabeledContent("版本", value: appVersion)
                    LabeledContent("菜谱", value: "\(store.recipes.count) 道")
                    LabeledContent("数据版本", value: store.data.meta.dataVersion)
                    Link("网页版：食用手册", destination: URL(string: "https://cook.yunyoujun.cn")!)
                    Link("反馈与建议", destination: URL(string: "https://github.com/YunYouJun/cook/issues")!)
                }
            }
            .navigationTitle("我的")
        }
    }
}
