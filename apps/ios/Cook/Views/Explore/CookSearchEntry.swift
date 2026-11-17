import SwiftUI

/// A stable entry point shared by the ingredient list and cloud kitchen.
struct CookSearchEntry: View {
    var message: String?
    let openSearch: (CGFloat) -> Void
    @Environment(CookSession.self) private var session
    @Environment(\.cookSearchExpansion) private var expansion
    @Environment(\.cookSearchSourceOffset) private var sourceScrollOffset

    var body: some View {
        VStack(alignment: .leading, spacing: CookLayout.Spacing.small) {
            searchButton
                .opacity(expansion?.hidesSourceField == true ? 0 : 1)
                .accessibilityHidden(expansion?.isPresented == true)
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

    private var searchButton: some View {
        Button {
            openSearch(sourceScrollOffset)
        } label: {
            HStack(spacing: CookLayout.Spacing.small) {
                CookSearchMagnifier()
                Text("菜名、食材或一句话")
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                Image(systemName: expansion == nil ? "chevron.right" : "arrow.up.left.and.arrow.down.right")
                    .font(.footnote)
                    .frame(width: CookLayout.SearchField.actionWidth)
            }
            .font(.body)
            .foregroundStyle(.secondary)
            .padding(.leading, CookLayout.SearchField.leadingInset)
            .padding(.trailing, CookLayout.SearchField.trailingInset)
            .padding(.vertical, CookLayout.Spacing.small)
            .frame(maxWidth: .infinity, minHeight: CookLayout.searchFieldHeight, alignment: .leading)
            .background(CookPalette.inputBackground, in: RoundedRectangle(cornerRadius: CookLayout.fieldCornerRadius))
            .contentShape(RoundedRectangle(cornerRadius: CookLayout.fieldCornerRadius))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("搜索菜名、食材或一句话")
        .accessibilityHint("打开搜索页，查看最近搜索或输入条件")
        .accessibilityIdentifier("cook.search.open")
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
