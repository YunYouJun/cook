import SwiftUI

/// A stable entry point shared by the ingredient list and cloud kitchen.
struct CookSearchEntry: View {
    var message: String?
    let openSearch: () -> Void
    @Environment(CookSession.self) private var session

    var body: some View {
        VStack(alignment: .leading, spacing: CookLayout.Spacing.small) {
            Button(action: openSearch) {
                HStack(spacing: CookLayout.Spacing.small) {
                    Image(systemName: "magnifyingglass")
                    Text("菜名、食材或一句话")
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.right").font(.footnote)
                }
                .font(.body)
                .foregroundStyle(.secondary)
                .padding(.horizontal, CookLayout.Spacing.large)
                .padding(.vertical, CookLayout.Spacing.small)
                .frame(maxWidth: .infinity, minHeight: CookLayout.searchFieldHeight, alignment: .leading)
                .background(CookPalette.inputBackground, in: RoundedRectangle(cornerRadius: CookLayout.fieldCornerRadius))
                .contentShape(RoundedRectangle(cornerRadius: CookLayout.fieldCornerRadius))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("搜索菜名、食材或一句话")
            .accessibilityHint("打开搜索页，查看最近搜索或输入条件")
            .accessibilityIdentifier("cook.search.open")
            if let message {
                Text(message).searchEntryCaption()
            } else if !session.hasSelection {
                Text("试试：有土豆和鸡蛋，不要辣").searchEntryCaption()
            }
        }
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .listRowSeparator(.hidden)
    }
}

private extension Text {
    func searchEntryCaption() -> some View {
        font(.footnote)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, CookLayout.Spacing.medium)
    }
}
