import CookCore
import SwiftData
import SwiftUI

@main
struct CookApp: App {
    @State private var loadResult: Result<RecipeStore, Error>
    @State private var session: CookSession
    private let container: ModelContainer

    init() {
        let result = Result { try RecipeStore.loadBundled() }
        let defaultPantry = (try? result.get())?.data.pantry.defaults ?? []
        _loadResult = State(initialValue: result)
        _session = State(initialValue: CookSession(defaultPantry: defaultPantry))
        container = Persistence.makeContainer()
    }

    var body: some Scene {
        WindowGroup {
            switch loadResult {
            case let .success(store):
                RootView()
                    .environment(store)
                    .environment(session)
            case let .failure(error):
                ContentUnavailableView(
                    "菜谱数据加载失败",
                    systemImage: "exclamationmark.triangle",
                    description: Text(error.localizedDescription)
                )
            }
        }
        .modelContainer(container)
    }
}
