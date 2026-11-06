#if DEBUG
import CookCore
import Foundation

/// Explicit UI-test fixture. It never contacts an account or production service.
actor UserDataSyncFixture {
    private var document = UserDataDocument()
    func respond(_ request: URLRequest) throws -> (Data, URLResponse) {
        let fields = try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any]
        if let raw = fields?["document"] {
            document = document.merging(try UserDataDocument.decode(JSONSerialization.data(withJSONObject: raw)))
        }
        let result: [String: Any] = ["ok": true, "protocolVersion": 2,
                                     "document": try JSONSerialization.jsonObject(with: JSONEncoder().encode(document))]
        return (try JSONSerialization.data(withJSONObject: result),
                HTTPURLResponse(url: UserDataSyncService.endpoint, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}
#endif
