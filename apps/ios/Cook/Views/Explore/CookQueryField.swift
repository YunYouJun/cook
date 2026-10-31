import SwiftUI

/// 列表和厨房布局共用的行内输入，不需要先选择搜索方式。
struct CookQueryField: View {
    @Binding var text: String
    var message: String? = nil
    let submit: () -> Void
    var preview: (() -> Void)? = nil
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 4) {
                    query
                    submitButton
                }
            } else {
                HStack(spacing: 8) {
                    query
                    submitButton
                }
            }
            if let preview, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Button {
                    focused = false
                    preview()
                } label: {
                    Label("预览条件", systemImage: "slider.horizontal.3")
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .font(.subheadline)
                .labelStyle(.titleAndIcon)
                .buttonStyle(.borderless)
                .accessibilityHint("先确认识别的条件，也可以在预览里选择智能识别")
                .accessibilityIdentifier("intent.preview")
            }
            if let message, !message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .listRowInsets(EdgeInsets(top: 0, leading: 12, bottom: 0, trailing: 12))
    }

    private var query: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            TextField("菜名、食材，或说一句话", text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($focused)
                .onSubmit(performSearch)
                .accessibilityLabel("输入菜名或食材")
                .accessibilityHint("也可以用一句话描述想吃什么")
                .accessibilityIdentifier("cook.query")
        }
        .frame(minHeight: 44)
    }

    private var submitButton: some View {
        Button(action: performSearch) {
            HStack(spacing: 8) {
                Image(systemName: "arrow.right.circle.fill")
                if dynamicTypeSize.isAccessibilitySize {
                    Text("查找")
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .font(dynamicTypeSize.isAccessibilitySize ? .body : .title2)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        .accessibilityLabel("查找")
        .accessibilityIdentifier("cook.submitQuery")
    }

    private func performSearch() {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        submit()
        focused = false
    }
}
