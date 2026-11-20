import CookCore
import ImageIO
import UIKit
import UniformTypeIdentifiers
import XCTest
@testable import Cook

@MainActor
final class FridgeVisionTests: XCTestCase {
    func testSelectedNamesNormalizeAliasesAndIgnoreUncheckedRows() {
        let normalizer = StuffNormalizer(aliases: ["西红柿": "番茄"])
        let rows = [FridgeVisionCandidate(name: " 西红柿 "), FridgeVisionCandidate(name: "番茄"),
                    FridgeVisionCandidate(name: "牛奶", included: false), FridgeVisionCandidate(name: "鸡蛋")]
        XCTAssertEqual(FridgeVisionCandidate.names(rows, normalizer: normalizer), ["番茄", "鸡蛋"])
        XCTAssertNil(FridgeVisionCandidate.names(rows + [FridgeVisionCandidate(name: "苹果、香蕉")], normalizer: normalizer))
    }

    func testUploadPreparationResizesAndDropsOriginalGPSMetadata() async throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let picture = try XCTUnwrap(UIGraphicsImageRenderer(size: CGSize(width: 2000, height: 100), format: format).image { renderer in
            UIColor.systemGreen.setFill()
            renderer.fill(CGRect(x: 0, y: 0, width: 2000, height: 100))
        }.cgImage)
        let bytes = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(bytes, UTType.jpeg.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, picture, [
            kCGImagePropertyOrientation: 6,
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 30.0, kCGImagePropertyGPSLatitudeRef: "N"],
        ] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        let original = try XCTUnwrap(CGImageSourceCreateWithData(bytes, nil))
        let originalProperties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(original, 0, nil) as? [String: Any])
        XCTAssertNotNil(originalProperties[kCGImagePropertyGPSDictionary as String])

        let photo = try await FridgeVisionPhoto.prepare(bytes as Data)
        let source = try XCTUnwrap(CGImageSourceCreateWithData(photo.jpeg as CFData, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any])
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary as String])
        XCTAssertEqual(properties[kCGImagePropertyPixelHeight as String] as? Int, 1600)
        XCTAssertEqual(properties[kCGImagePropertyPixelWidth as String] as? Int, 80)
        XCTAssertLessThanOrEqual(photo.jpeg.count, FridgeVisionPhoto.maxJPEGBytes)
    }

    func testRequestKeepsPhotoAndConsentBoundedAndDoesNotSendRefreshToken() async throws {
        let endpoint = FridgeVisionService.productionURL
        let photo = FridgeVisionPhoto(jpeg: Data(repeating: 255, count: FridgeVisionPhoto.maxJPEGBytes))
        let service = FridgeVisionService(endpoint: endpoint) { request in
            let body = try XCTUnwrap(request.httpBody)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            XCTAssertEqual(Set(json.keys), ["image", "schemaVersion", "consentVersion", "provider"])
            XCTAssertEqual(json["provider"] as? String, "cloudbase")
            XCTAssertEqual(json["image"] as? String, photo.jpeg.base64EncodedString())
            XCTAssertLessThan(body.count, 4 * ((photo.jpeg.count + 2) / 3) + 128)
            XCTAssertLessThan(body.count, 94 * 1024, "Leave room under the measured HTTP gateway limit")
            XCTAssertEqual(json["consentVersion"] as? Int, 1)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
            let reply = Data(#"{"ok":true,"schemaVersion":1,"ingredients":["鸡蛋","牛奶"]}"#.utf8)
            return (reply, HTTPURLResponse(url: endpoint, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let credential = YunlefunCredential(accessToken: "access", refreshToken: "refresh", subject: "subject",
                                            expiresAt: .now.addingTimeInterval(600), deviceID: "device")
        let result = try await service.recognize(photo, provider: .cloudBase, credential: credential)
        XCTAssertEqual(result, ["鸡蛋", "牛奶"])
    }

    func testDiscoveringPhotoRecipientSendsNeitherPhotoNorAccount() async throws {
        let endpoint = FridgeVisionService.productionURL
        let service = FridgeVisionService(endpoint: endpoint) { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertNil(request.httpBody)
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertEqual(request.cachePolicy, .reloadIgnoringLocalCacheData)
            let reply = Data(#"{"ok":true,"schemaVersion":1,"provider":"cloudbase"}"#.utf8)
            return (reply, HTTPURLResponse(url: endpoint, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let provider = try await service.activeProvider()
        XCTAssertEqual(provider, .cloudBase)
        XCTAssertTrue(provider.photoProcessingDescription.contains("腾讯云 CloudBase"))
    }

    func testChecksMembershipWithOnlyTheAccountTokenBeforeAPhotoIsSelected() async throws {
        let endpoint = FridgeVisionService.productionURL
        let service = FridgeVisionService(endpoint: endpoint) { request in
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertNil(request.httpBody)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access")
            let reply = Data(#"{"ok":true,"schemaVersion":1,"provider":"deepseek","member":false}"#.utf8)
            return (reply, HTTPURLResponse(url: endpoint, statusCode: 200, httpVersion: nil, headerFields: nil)!)
        }
        let credential = YunlefunCredential(accessToken: "access", refreshToken: "refresh", subject: "subject",
                                            expiresAt: .now.addingTimeInterval(600), deviceID: "device")
        let member = try await service.hasMembership(credential: credential)
        XCTAssertFalse(member)
    }

    func testAnUploadDeniedAfterMembershipExpiresHasASpecificError() async throws {
        let endpoint = FridgeVisionService.productionURL
        let service = FridgeVisionService(endpoint: endpoint) { _ in
            (Data(#"{"ok":false,"code":"MEMBERSHIP_REQUIRED"}"#.utf8),
             HTTPURLResponse(url: endpoint, statusCode: 403, httpVersion: nil, headerFields: nil)!)
        }
        let credential = YunlefunCredential(accessToken: "access", refreshToken: "refresh", subject: "subject",
                                            expiresAt: .now.addingTimeInterval(600), deviceID: "device")
        do {
            _ = try await service.recognize(FridgeVisionPhoto(jpeg: Data([1])), provider: .deepSeek, credential: credential)
            XCTFail("An expired membership must not allow the upload")
        } catch FridgeVisionError.membershipRequired { }
    }

    func testProviderChangeRequiresAnotherConsent() async throws {
        let endpoint = FridgeVisionService.productionURL
        let service = FridgeVisionService(endpoint: endpoint) { _ in
            (Data(), HTTPURLResponse(url: endpoint, statusCode: 409, httpVersion: nil, headerFields: nil)!)
        }
        let credential = YunlefunCredential(accessToken: "access", refreshToken: "refresh", subject: "subject",
                                            expiresAt: .now.addingTimeInterval(600), deviceID: "device")
        do {
            _ = try await service.recognize(FridgeVisionPhoto(jpeg: Data([1])), provider: .qwen, credential: credential)
            XCTFail("Previous consent must not carry over to a different provider")
        } catch FridgeVisionError.providerChanged {
            // The preview can fetch the new recipient without repeating the upload.
        }
    }
}
