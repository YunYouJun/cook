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
            CookSearchSupport(
                text: $input,
                focused: $focused,
                submitExample: submit,
                preview: previewConditions,
                addIngredient: addIngredient
            )
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(CookLayout.Spacing.large)
        }
        .accessibilityIdentifier("cook.search.content")
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .top, spacing: 0) {
            CookQueryField(text: $input, focused: $focused) { submit(input) }
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
        .onAppear { focused = true }
    }

    private func submit(_ text: String) {
        guard preview == nil else { return }
        let result = session.applyDiscoveryInput(text, in: store)
        guard result.kind != .empty else { return }
        history.record(text)
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

    func cookHomeActionBar<Bar: View>(background: Color, isSearchPresented: Bool, @ViewBuilder _ bar: @escaping () -> Bar) -> some View {
        modifier(CookHomeActionBar(background: background, isSearchPresented: isSearchPresented, bar: bar))
    }
}

private struct CookHomeActionBar<Bar: View>: ViewModifier {
    let background: Color
    let isSearchPresented: Bool
    let bar: () -> Bar
    @State private var restingLayout: Layout?
    @State private var returningFromSearch = false

    func body(content: Content) -> some View {
        GeometryReader { geometry in
            let layout = Layout(width: geometry.size.width, bottomInset: geometry.safeAreaInsets.bottom)
            let hasRestoredInset = restingLayout.map {
                abs(layout.width - $0.width) > 1 || layout.bottomInset >= $0.bottomInset - 0.5
            } ?? true
            // SwiftUI can start showing home before the native tab bar is restored.
            // Reserve the button's space and reveal it at its resting position.
            let showsAction = !(isSearchPresented || returningFromSearch) || hasRestoredInset
            content.bottomActionBar(background: background) {
                bar()
                    .opacity(showsAction ? 1 : 0)
                    .allowsHitTesting(showsAction)
                    .accessibilityHidden(!showsAction)
            }
            .onChange(of: Observation(layout: layout, isSearchPresented: isSearchPresented), initial: true) {
                if isSearchPresented {
                    returningFromSearch = true
                } else if showsAction {
                    restingLayout = layout
                    returningFromSearch = false
                }
            }
        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }

    private struct Layout: Equatable {
        let width: CGFloat
        let bottomInset: CGFloat
    }

    private struct Observation: Equatable {
        let layout: Layout
        let isSearchPresented: Bool
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
