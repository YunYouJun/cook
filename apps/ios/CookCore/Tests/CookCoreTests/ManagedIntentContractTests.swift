import CookCore
import Foundation
import XCTest

final class ManagedIntentContractTests: XCTestCase {
    private struct ServerContract: Decodable {
        let schemaVersion: Int
        let policyVersion: Int
        let dataVersion: String
        let stuff: [String]
        let tools: [String]
        let difficulty: [String]
        let methodGroups: [String]
        let availableGoals: [DietaryGoal]
    }

    func testManagedServerContractMatchesCurrentNativeVocabularyVersionsAndReviewedGoals() throws {
        let data = try SharedFixtureTests.loadData()
        let directory = SharedFixtureTests.dataDirectory
        let contractURL = directory.deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("cloudfunctions/cook-ai-intent/contract.json")
        let contract = try JSONDecoder().decode(ServerContract.self, from: Data(contentsOf: contractURL))
        let vocabulary = SearchIntentVocabulary(data: data)
        let dietary = try DietaryCatalog.load(from: directory, recipes: data.recipes)

        XCTAssertEqual(contract.schemaVersion, SearchIntent.currentSchemaVersion)
        XCTAssertEqual(contract.policyVersion, SearchIntent.currentPolicyVersion)
        XCTAssertEqual(contract.dataVersion, data.meta.dataVersion)
        XCTAssertEqual(contract.stuff.sorted(), vocabulary.stuff.sorted())
        XCTAssertEqual(contract.tools.sorted(), vocabulary.tools.sorted())
        XCTAssertEqual(contract.difficulty.sorted(), vocabulary.difficulty.sorted())
        XCTAssertEqual(contract.methodGroups.sorted(), vocabulary.methodGroups.sorted())
        XCTAssertEqual(contract.availableGoals.map(\.rawValue).sorted(), dietary.availableGoals.map(\.rawValue).sorted())
    }
}
