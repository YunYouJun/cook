import SwiftUI

/// A single input surface pinned above the search page’s scrollable assistance.
struct CookQueryField: View {
    private typealias Space = CookLayout.Spacing

    @Binding var text: String
    @FocusState.Binding var focused: Bool
    let submit: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var canSearch: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        searchSurface
    }

    private var searchSurface: some View {
        let shape = RoundedRectangle(cornerRadius: CookLayout.fieldCornerRadius, style: .continuous)
        return Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: Space.xSmall) {
                    query
                    submitButton
                }
                .padding(.vertical, Space.small)
            } else {
                HStack(spacing: Space.xSmall) {
                    query
                    submitButton
                }
            }
        }
        .padding(.horizontal, Space.medium)
        .frame(minHeight: CookLayout.searchFieldHeight)
        .background(CookPalette.inputBackground, in: shape)
        .overlay {
            if focused {
                shape.strokeBorder(CookPalette.primaryAction.opacity(0.6))
                    .allowsHitTesting(false)
            }
        }
    }

    private var query: some View {
        HStack(spacing: Space.small) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            TextField("菜名、食材或一句话", text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($focused)
                .onSubmit(performSearch)
                .tint(CookPalette.primaryAction)
                .accessibilityLabel("输入菜名或食材")
                .accessibilityHint("也可以用一句话描述想吃什么")
                .accessibilityIdentifier("cook.query")
                .frame(minHeight: CookLayout.minimumHitSize)
            if !text.isEmpty {
                Button {
                    text = ""
                    focused = true
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                        .frame(minWidth: CookLayout.minimumHitSize, minHeight: CookLayout.minimumHitSize)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清除输入")
                .accessibilityIdentifier("cook.clearInput")
            }
        }
        .font(.body)
    }

    private var submitButton: some View {
        Button(action: performSearch) {
            HStack(spacing: Space.small) {
                Image(systemName: "arrow.right.circle.fill")
                if dynamicTypeSize.isAccessibilitySize {
                    Text("查找")
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .font(dynamicTypeSize.isAccessibilitySize ? .body : .title2)
            .foregroundStyle(canSearch ? CookPalette.primaryAction : Color.secondary)
            .frame(minWidth: CookLayout.minimumHitSize, minHeight: CookLayout.minimumHitSize)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!canSearch)
        .accessibilityLabel("查找")
        .accessibilityIdentifier("cook.submitQuery")
    }

    private func performSearch() {
        guard canSearch else { return }
        submit()
        focused = false
    }
}
