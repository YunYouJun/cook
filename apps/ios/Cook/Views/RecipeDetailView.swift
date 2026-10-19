import CookCore
import SwiftData
import SwiftUI

/// 菜谱详情：已有、还缺、常备分开列出，可以收藏、分享、去 B 站看做法
struct RecipeDetailView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session
    @Environment(FeedbackClient.self) private var feedback
    @Environment(\.modelContext) private var context
    @Environment(\.openURL) private var openURL
    @State private var choosingIssue = false
    @State private var writingNote = false
    @State private var note = ""
    @State private var showingSent = false
    @State private var showingFailed = false
    @Query(filter: #Predicate<Favorite> { $0.deletedAt == nil }) private var favorites: [Favorite]
    let route: RecipeRoute

    private var recipe: Recipe {
        route.recipe
    }

    private var isFavorite: Bool {
        favorites.contains { $0.recipeId == recipe.id }
    }

    private struct IngredientGroup {
        let label: String
        let items: [String]
    }

    private var groups: [IngredientGroup] {
        guard let match = route.match else {
            return [IngredientGroup(label: "食材", items: store.data.normalizer.unique(recipe.stuff))]
        }
        return [
            IngredientGroup(label: "已有", items: match.matchedStuff),
            IngredientGroup(label: "还缺", items: match.missingStuff),
            IngredientGroup(label: "常备调料", items: match.pantryStuff),
            IngredientGroup(label: "还能加", items: match.optionalStuff),
        ].filter { !$0.items.isEmpty }
    }

    /// 视频网页：优先用记录的链接，其次 B 站视频号
    private var webURL: URL? {
        if let link = recipe.link, !link.isEmpty {
            return URL(string: link)
        }
        return recipe.videoURL
    }

    private var feedbackURL: URL {
        var components = URLComponents(string: "https://github.com/YunYouJun/cook/issues/new")!
        components.queryItems = [
            URLQueryItem(name: "template", value: "recipe.yml"),
            URLQueryItem(name: "title", value: "[菜谱] \(recipe.name)"),
            URLQueryItem(name: "name", value: recipe.name),
            URLQueryItem(name: "video", value: webURL?.absoluteString ?? ""),
        ]
        return components.url!
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 8) {
                    Text(store.thumbnail(for: recipe))
                        .font(.system(size: 64))
                        .accessibilityHidden(true)
                    Text(store.meta(for: recipe))
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }

            ForEach(groups, id: \.label) { group in
                Section(group.label) {
                    Text(group.items.map { name in
                        store.emoji(forStuff: name).map { "\($0) \(name)" } ?? name
                    }.joined(separator: "、"))
                    // 读屏时不念 emoji 的名字，只念食材
                    .accessibilityLabel(group.items.joined(separator: "、"))
                }
            }

            if !recipe.methods.isEmpty || !recipe.tags.isEmpty {
                Section("做法和标签") {
                    if !recipe.methods.isEmpty {
                        LabeledContent("做法", value: recipe.methods.joined(separator: "、"))
                    }
                    if !recipe.tags.isEmpty {
                        LabeledContent("标签", value: recipe.tags.joined(separator: "、"))
                    }
                }
            }

            Section {
                if webURL != nil {
                    Button(action: openVideo) {
                        Label(recipe.bv == nil ? "看做法" : "去 B 站看做法", systemImage: "play.rectangle")
                    }
                }
                Button {
                    // 没配置反馈地址的构建（比如本机调试）直接去 GitHub
                    if feedback.isConfigured {
                        choosingIssue = true
                    } else {
                        openURL(feedbackURL)
                    }
                } label: {
                    Label("反馈这道菜的问题", systemImage: "exclamationmark.bubble")
                }
                .confirmationDialog("这道菜有什么问题？", isPresented: $choosingIssue, titleVisibility: .visible) {
                    ForEach(FeedbackClient.RecipeIssue.allCases) { issue in
                        Button(issue.label) {
                            if issue == .other {
                                note = ""
                                writingNote = true
                            } else {
                                submit(issue)
                            }
                        }
                    }
                }
                .alert("具体是什么问题？", isPresented: $writingNote) {
                    TextField("比如：用量不对、步骤有误", text: $note)
                    Button("取消", role: .cancel) {}
                    Button("提交") { submit(.other, note: note) }
                }
            }
        }
        .alert("收到了，谢谢反馈", isPresented: $showingSent) {
            Button("好", role: .cancel) {}
        }
        .alert("暂时没能提交", isPresented: $showingFailed) {
            Button("取消", role: .cancel) {}
            Button("去 GitHub 填写") { openURL(feedbackURL) }
        } message: {
            Text("可以到 GitHub 填写反馈表单，菜名和视频已经帮你填好。")
        }
        // 菜名只放在大标题里，滚动后收进导航栏，不在页面里再写一遍
        .navigationTitle(recipe.name)
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    context.toggleFavorite(recipe.id)
                } label: {
                    Label(isFavorite ? "取消收藏" : "收藏", systemImage: isFavorite ? "heart.fill" : "heart")
                        .contentTransition(.symbolEffect(.replace))
                        .symbolEffect(.bounce, value: isFavorite)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if let webURL {
                    ShareLink(item: webURL, subject: Text(recipe.name)) {
                        Label("分享", systemImage: "square.and.arrow.up")
                    }
                }
            }
        }
        .sensoryFeedback(.success, trigger: isFavorite)
        // iPad 分栏里换一道菜时视图不会重建，按菜谱 id 记浏览
        .task(id: recipe.id) {
            context.recordVisit(recipe.id)
            // 从推荐结果点进来时，匿名记录这组食材下点开了第几个，用来评估排序
            if let rank = route.rank {
                feedback.signalOpen(stuff: session.selectedStuff, tool: session.tool, mode: session.mode, recipeId: recipe.id, rank: rank)
            }
        }
        .sensoryFeedback(.success, trigger: showingSent) { _, sent in sent }
    }

    private func submit(_ issue: FeedbackClient.RecipeIssue, note: String = "") {
        Task {
            do {
                try await feedback.report(recipe, issue: issue, note: note)
                showingSent = true
            } catch {
                showingFailed = true
            }
        }
    }

    /// 装了 B 站 App 就直接打开，否则用浏览器
    private func openVideo() {
        guard let webURL else {
            return
        }
        guard let bv = recipe.bv, !bv.isEmpty, recipe.link?.isEmpty ?? true,
              let appURL = URL(string: "bilibili://video/\(bv)")
        else {
            openURL(webURL)
            return
        }
        openURL(appURL) { accepted in
            if !accepted {
                openURL(webURL)
            }
        }
    }
}
