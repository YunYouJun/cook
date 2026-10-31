import SwiftUI
import UIKit

enum CookPresentation: String {
    case list
    case kitchen
}

/// 标题栏中的视图菜单，同时展示两个选项和当前选择，不占用食材快选区域。
struct CookPresentationToggle: UIViewRepresentable {
    @Binding var selection: CookPresentation
    // 工具栏会限制自己的动态字号；菜单跟随页面读到的实际辅助字号设置。
    var usesCurrentSubtitle: Bool

    private var selectionName: String { selection == .list ? "食材快选" : "云厨房" }

    func makeCoordinator() -> Coordinator { Coordinator(selection: $selection) }

    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .system)
        var configuration = UIButton.Configuration.plain()
        configuration.title = "视图"
        configuration.image = UIImage(systemName: "chevron.down", withConfiguration: UIImage.SymbolConfiguration(pointSize: 12, weight: .semibold))
        configuration.imagePlacement = .trailing
        configuration.imagePadding = 4
        configuration.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4)
        configuration.baseForegroundColor = .label
        button.configuration = configuration
        button.showsMenuAsPrimaryAction = true
        button.setContentHuggingPriority(.required, for: .horizontal)
        button.setContentCompressionResistancePriority(.required, for: .horizontal)
        return button
    }

    func updateUIView(_ button: UIButton, context: Context) {
        context.coordinator.selection = $selection
        let coordinator = context.coordinator
        let quick = UIAction(title: "食材快选", image: UIImage(systemName: "list.bullet"), state: !usesCurrentSubtitle && selection == .list ? .on : .off) { [weak coordinator] _ in
            coordinator?.selection.wrappedValue = .list
        }
        let kitchen = UIAction(title: "云厨房", image: UIImage(systemName: "cube"), state: !usesCurrentSubtitle && selection == .kitchen ? .on : .off) { [weak coordinator] _ in
            coordinator?.selection.wrappedValue = .kitchen
        }
        if usesCurrentSubtitle {
            // 系统勾选栏在最大辅助字号下可能与首字接触，改用可缩放的文字说明当前项。
            quick.subtitle = selection == .list ? "当前视图" : nil
            kitchen.subtitle = selection == .kitchen ? "当前视图" : nil
        }
        button.menu = UIMenu(options: usesCurrentSubtitle ? [] : .singleSelection, children: [quick, kitchen])
        button.isEnabled = context.environment.isEnabled
        button.accessibilityLabel = "切换视图"
        button.accessibilityValue = selectionName
        button.accessibilityHint = "选择食材快选或云厨房，保留已选食材和筛选条件"
        button.accessibilityIdentifier = "cook.presentation"
    }

    // SwiftUI 的工具栏 Menu 会被压成 36pt；原生按钮在这里明确申请完整命中范围。
    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UIButton, context: Context) -> CGSize? {
        let size = uiView.intrinsicContentSize
        return CGSize(width: max(44, size.width), height: max(44, size.height))
    }

    final class Coordinator {
        var selection: Binding<CookPresentation>
        init(selection: Binding<CookPresentation>) { self.selection = selection }
    }
}
