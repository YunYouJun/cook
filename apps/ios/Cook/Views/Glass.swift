import SwiftUI

// iOS 26 起用 Liquid Glass，旧系统（最低 iOS 17）退回到原来的样式。
// 按 HIG：玻璃只用在浮在内容上面的控件（底部按钮、提示条），菜谱卡片这类内容本身不用。

extension View {
    /// 页面底部的主按钮
    @ViewBuilder
    func prominentButtonStyle() -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.glassProminent)
        } else {
            buttonStyle(.borderedProminent)
        }
    }

    /// 底部固定的操作栏。iOS 26 用 safeAreaBar，系统会给下面滚过的内容加渐隐的边缘效果；
    /// 旧系统垫一层和背景同色的渐变，免得半透明的按钮和列表叠在一起
    func bottomActionBar<Bar: View>(@ViewBuilder _ bar: @escaping () -> Bar) -> some View {
        modifier(BottomActionBar(bar: bar))
    }

    /// 浮在图片上的小控件（关闭按钮、页码、图片说明）。iOS 26 用玻璃，能点的加上按压反馈；旧系统用磨砂材质
    @ViewBuilder
    func floatingGlass(in shape: some Shape, interactive: Bool = false) -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        } else {
            background(.ultraThinMaterial, in: shape)
        }
    }

    /// iOS 26 起在下滑时把 Tab 栏收小，给内容多留地方
    @ViewBuilder
    func minimizesTabBarOnScroll() -> some View {
        if #available(iOS 26.0, *) {
            tabBarMinimizeBehavior(.onScrollDown)
        } else {
            self
        }
    }
}

private struct BottomActionBar<Bar: View>: ViewModifier {
    let bar: () -> Bar

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.safeAreaBar(edge: .bottom) {
                bar()
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
        } else {
            content.safeAreaInset(edge: .bottom) {
                bar()
                    .padding(.horizontal)
                    .padding(.top, 16)
                    .padding(.bottom, 8)
                    .background {
                        let background = Color(uiColor: .systemGroupedBackground)
                        VStack(spacing: 0) {
                            LinearGradient(colors: [background.opacity(0), background], startPoint: .top, endPoint: .bottom)
                                .frame(height: 16)
                            background
                        }
                        .ignoresSafeArea(edges: .bottom)
                    }
            }
        }
    }
}
