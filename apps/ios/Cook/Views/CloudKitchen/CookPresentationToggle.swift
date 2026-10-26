import SwiftUI

enum CookPresentation: String {
    case list
    case kitchen
}

/// A one-tap display choice, kept in the navigation bar so it does not consume
/// a row of the ingredient list or kitchen canvas.
struct CookPresentationToggle: View {
    @Binding var selection: CookPresentation

    private var destinationName: String { selection == .list ? "云厨房" : "列表" }

    var body: some View {
        Button {
            selection = selection == .list ? .kitchen : .list
        } label: {
            Label(destinationName, systemImage: selection == .list ? "cube" : "list.bullet")
        }
        .labelStyle(.iconOnly)
        .accessibilityLabel("切换到\(destinationName)")
        .accessibilityHint("保留已选食材和筛选条件")
        .accessibilityIdentifier("cook.presentation")
        .help("切换到\(destinationName)")
    }
}
