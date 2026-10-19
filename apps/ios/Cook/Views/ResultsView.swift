import CookCore
import SwiftUI

/// 推荐结果：按缺几样分组，可以切换推荐方式、按菜名搜
struct ResultsView: View {
    @Environment(RecipeStore.self) private var store
    @Environment(CookSession.self) private var session

    static let modes: [RecommendationMode] = [.loose, .strict, .survival]
    static func label(for mode: RecommendationMode) -> String {
        switch mode {
        case .loose: return "全部推荐"
        case .strict: return "用上所有已选"
        case .survival: return "只看食材齐全"
        }
    }

    static let groupLabels = ["齐全", "差一种", "差两种"]
    static let groupHints = ["食材都有", "补一种就能做", "补两种就能做"]

    private var filterSummary: [String] {
        var items: [String] = []
        if let tool = session.tool {
            items.append(store.toolLabel(tool))
        }
        items += session.methodGroups + session.difficulty
        if session.noSpicy {
            items.append("不吃辣")
        }
        return items
    }

    var body: some View {
        @Bindable var session = session
        let matches = store.recommender.recommend(session.options)
        let ranks = Dictionary(matches.enumerated().map { ($1.recipe.id, $0) }, uniquingKeysWith: { first, _ in first })

        List {
            Section {
                Picker("推荐方式", selection: $session.mode) {
                    ForEach(Self.modes, id: \.self) { mode in
                        Text(Self.label(for: mode)).tag(mode)
                    }
                }
            } footer: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("已选：\(session.selectedStuff.joined(separator: "、"))")
                    // 筛选条件在做菜页的 sheet 里设置，这里也写出来，免得结果变少时不知道为什么
                    if !filterSummary.isEmpty {
                        Text("筛选：\(filterSummary.joined(separator: "、"))")
                    }
                }
            }

            if matches.isEmpty {
                ContentUnavailableView(
                    "没有找到能做的菜",
                    systemImage: "fork.knife",
                    description: Text("换几样食材，或者把推荐方式改成「全部推荐」。")
                )
            }

            ForEach(0 ..< Self.groupLabels.count, id: \.self) { missing in
                let items = matches.filter { $0.missingStuff.count == missing }
                if !items.isEmpty {
                    Section {
                        ForEach(items, id: \.recipe.id) { match in
                            NavigationLink(value: RecipeRoute(recipe: match.recipe, match: match, rank: ranks[match.recipe.id])) {
                                RecipeRow(recipe: match.recipe, match: match)
                            }
                        }
                    } header: {
                        Text("\(Self.groupLabels[missing]) · \(items.count)")
                    } footer: {
                        Text(Self.groupHints[missing])
                    }
                }
            }
        }
        .navigationTitle("能做的菜")
        .searchable(text: $session.keyword, placement: .navigationBarDrawer(displayMode: .always), prompt: "按菜名筛选")
    }
}

/// 列表里的一道菜
struct RecipeRow: View {
    @Environment(RecipeStore.self) private var store
    let recipe: Recipe
    var match: RecipeMatch? = nil

    var body: some View {
        HStack(spacing: 12) {
            Text(store.thumbnail(for: recipe))
                .font(.largeTitle)
                .frame(minWidth: 44)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(recipe.name)
                    .font(.headline)
                Text(store.meta(for: recipe))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                if let match, !match.missingStuff.isEmpty {
                    Text("还缺 \(match.missingStuff.joined(separator: "、"))")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
