#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^CookWeChatResponse)(NSInteger status, NSString *_Nullable state, NSString *_Nullable code);

/// Keeps Tencent's SDK and its device-only library out of the regular Swift build.
@interface CookWeChatBridge : NSObject
+ (BOOL)prepare:(NSString *)appID universalLink:(NSString *)universalLink NS_SWIFT_NAME(prepare(appID:universalLink:));
+ (BOOL)isLinked NS_SWIFT_NAME(isLinked());
+ (void)sendAuth:(NSString *)state completion:(void (^)(BOOL))completion NS_SWIFT_NAME(sendAuth(state:completion:));
+ (BOOL)handleURL:(NSURL *)url response:(CookWeChatResponse)response NS_SWIFT_NAME(handle(url:response:));
+ (BOOL)handleActivity:(NSUserActivity *)activity response:(CookWeChatResponse)response NS_SWIFT_NAME(handle(activity:response:));
@end

NS_ASSUME_NONNULL_END
