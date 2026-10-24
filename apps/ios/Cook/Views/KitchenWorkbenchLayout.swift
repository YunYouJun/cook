import SwiftUI

/// 使用实际分区的坐标；导航栏、安全区域和不对称折痕都不能按屏幕中点推算。
struct KitchenWorkbenchFrames {
    enum Mode { case compact, columns, tabletop }
    let size: CGSize
    let mode: Mode
    let recipe: CGRect
    let preparation: CGRect
    let divider: CGRect

    static func resolve(size: CGSize, division: CGRect? = nil, tabletop: Bool, accessibility: Bool) -> Self {
        let bounds = CGRect(origin: .zero, size: size)
        if let division, !division.isNull, division.intersects(bounds) {
            let fold = division.intersection(bounds)
            // 系统分区可能延伸到当前容器之外，只接受真正把容器分成两部分的区域。
            if fold.width >= size.width - 1, fold.minY > 0, fold.maxY < size.height {
                return Self(size: size, mode: .tabletop,
                            recipe: CGRect(x: 0, y: 0, width: size.width, height: fold.minY),
                            preparation: CGRect(x: 0, y: fold.maxY, width: size.width, height: size.height - fold.maxY),
                            divider: .zero)
            }
            if fold.height >= size.height - 1, fold.minX > 0, fold.maxX < size.width {
                return Self(size: size, mode: .columns,
                            recipe: CGRect(x: 0, y: 0, width: fold.minX, height: size.height),
                            preparation: CGRect(x: fold.maxX, y: 0, width: size.width - fold.maxX, height: size.height),
                            divider: .zero)
            }
        }
        if tabletop, size.width >= 320, size.height >= 520, !accessibility {
            let middle = size.height / 2
            return Self(size: size, mode: .tabletop,
                        recipe: CGRect(x: 0, y: 0, width: size.width, height: middle - 8),
                        preparation: CGRect(x: 0, y: middle + 8, width: size.width, height: middle - 8),
                        divider: CGRect(x: 0, y: middle, width: size.width, height: 1))
        }
        let minimumColumnWidth: CGFloat = 320
        // 较窄但足够高的展开窗口也能容纳两栏；短横屏仍使用原有宽度门槛。
        let fitsTallColumns = size.width >= minimumColumnWidth * 2 + 1 && size.height >= 520
        if size.width >= 760 || fitsTallColumns, !accessibility {
            let sidebar = min(420, max(minimumColumnWidth, size.width * 0.38))
            let split = size.width - sidebar
            return Self(size: size, mode: .columns,
                        recipe: CGRect(x: 0, y: 0, width: split - 1, height: size.height),
                        preparation: CGRect(x: split, y: 0, width: sidebar, height: size.height),
                        divider: CGRect(x: split - 1, y: 0, width: 1, height: size.height))
        }
        let content = CGRect(x: 0, y: 48, width: size.width, height: max(0, size.height - 48))
        return Self(size: size, mode: .compact, recipe: content, preparation: content, divider: .zero)
    }
}

/// 同一对滚动视图只改变位置与尺寸，不因折叠/展开重建正文。
struct KitchenPaneLayout: Layout {
    let frames: KitchenWorkbenchFrames

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        frames.size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for (view, frame) in zip(subviews, [frames.recipe, frames.preparation]) {
            view.place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                       anchor: .topLeading, proposal: ProposedViewSize(frame.size))
        }
    }
}

extension GeometryProxy {
    var kitchenDivision: CGRect? {
        // Xcode 26.x 的构建仍支持 iOS 17；新 SDK 的分区 API 只在支持的系统上调用。
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            return reservedRegions(kind: .division).first.map { region in
                let frame = region.frame
                let margins = region.margins
                return CGRect(x: frame.minX - margins.leading, y: frame.minY - margins.top,
                              width: frame.width + margins.leading + margins.trailing,
                              height: frame.height + margins.top + margins.bottom)
            }
        }
        #endif
        return nil
    }
}
