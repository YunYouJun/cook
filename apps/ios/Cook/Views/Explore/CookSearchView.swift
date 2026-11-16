import CookCore
import SwiftUI

/// Draft input belongs to this destination; leaving it never changes the cooking session.
struct CookSearchView: View {
    var isModal = false
    let onApplied: (InputNotice) -> Void
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(CookSearchHistory.self) private var history
    @Environment(\.dismiss) private var dismiss
    @State private var input = ""
    @State private var preview: SearchPreview?
    @State private var previewApplied = false
    @FocusState private var focused: Bool

    var body: some View {
        ScrollView {
            CookSearchSupport(text: $input, focused: $focused, preview: previewConditions, addIngredient: addIngredient)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(CookLayout.Spacing.large)
        }
        .accessibilityIdentifier("cook.search.content")
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .top, spacing: 0) {
            CookQueryField(text: $input, focused: $focused, submit: submit)
                .padding(.horizontal, CookLayout.Spacing.large)
                .padding(.top, CookLayout.Spacing.small)
                .padding(.bottom, CookLayout.Spacing.medium)
                .background(Color(uiColor: .systemGroupedBackground))
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("搜索")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            if isModal {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .accessibilityIdentifier("cook.search.cancel")
                }
            }
        }
        .sheet(item: $preview, onDismiss: {
            if previewApplied {
                finish("已应用找菜条件。")
            } else {
                focused = true
            }
        }) { request in
            SearchIntentSheet(text: request.text) { previewApplied = true }
        }
        .task {
            // Wait for the navigation transition before asking the new field to focus.
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            focused = true
        }
    }

    private func submit() {
        guard preview == nil else { return }
        let result = session.applyDiscoveryInput(input, in: store)
        guard result.kind != .empty else { return }
        history.record(input)
        finish(result.message)
    }

    private func addIngredient(_ name: String) {
        let clearedKeyword = !session.keyword.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        session.keyword = ""
        session.addStuff(name)
        finish("已添加\(name)。" + (clearedKeyword ? "已清除上次菜名搜索。" : ""))
    }

    private func previewConditions() {
        let text = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        previewApplied = false
        preview = SearchPreview(text: text)
    }

    private func finish(_ message: String) {
        focused = false
        onApplied(InputNotice(kind: .applied, message: message))
        dismiss()
    }
}

private struct SearchPreview: Identifiable {
    let id = UUID()
    let text: String
}

extension View {
    /// Search is pushed on compact screens and presented as a focused task on wide screens.
    func cookSearch(isPresented: Binding<Bool>, onApplied: @escaping (InputNotice) -> Void) -> some View {
        modifier(CookSearchPresentation(isPresented: isPresented, onApplied: onApplied))
    }
}

private struct CookSearchPresentation: ViewModifier {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Binding var isPresented: Bool
    let onApplied: (InputNotice) -> Void

    func body(content: Content) -> some View {
        if sizeClass == .regular {
            content.sheet(isPresented: $isPresented) {
                NavigationStack { CookSearchView(isModal: true, onApplied: onApplied) }
                    .presentationDetents([.large])
            }
        } else {
            content.navigationDestination(isPresented: $isPresented) {
                CookSearchView(onApplied: onApplied)
            }
        }
    }
}
