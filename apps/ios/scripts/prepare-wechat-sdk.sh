#!/bin/bash
set -euo pipefail

# Standalone distribution listed by Tencent's WechatOpenSDK 2.0.9 podspec.
# Do not fetch or link the SDK in Simulator or ordinary device builds.
[[ "${PLATFORM_NAME:-}" == iphoneos ]] || exit 0
sdk_dir="${TARGET_TEMP_DIR}/CookWeChatSDK"
privacy_bundle="${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}/WeChatOpenSDKPrivacy.bundle"
if [[ "${COOK_WECHAT_LOGIN_ENABLED:-}" != YES ]]; then
  rm -rf -- "$privacy_bundle"
  exit 0
fi

version=2.0.9
sha=f40037787bb9ca8742d90b945cc82163bb123e156f9330316dd5df43782d351d
work=$(mktemp -d "${TARGET_TEMP_DIR}/wechat-sdk.XXXXXX")
trap 'rm -rf -- "$work"' EXIT
if [[ -n "${COOK_WECHAT_SDK_ARCHIVE:-}" ]]; then
  cp -- "$COOK_WECHAT_SDK_ARCHIVE" "$work/sdk.zip"
else
  curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
    --connect-timeout 10 --max-time 45 --output "$work/sdk.zip" \
    "https://dldir1.qq.com/WechatWebDev/opensdk/cocoapods/OpenSDK${version}.zip"
fi
if ! printf '%s  %s\n' "$sha" "$work/sdk.zip" | shasum -a 256 --check --status; then
  echo 'error: WeChat OpenSDK archive does not match the pinned SHA-256' >&2
  exit 1
fi
unzip -q "$work/sdk.zip" "OpenSDK${version}/WXApi.h" "OpenSDK${version}/WXApiObject.h" \
  "OpenSDK${version}/libWechatOpenSDK.a" "OpenSDK${version}/PrivacyInfo.xcprivacy" -d "$work"
vendor="$work/OpenSDK${version}"
mkdir -p "$sdk_dir" "$privacy_bundle"
install -m 644 "$vendor/WXApi.h" "$vendor/WXApiObject.h" "$vendor/libWechatOpenSDK.a" "$sdk_dir/"
install -m 644 "$vendor/PrivacyInfo.xcprivacy" "$privacy_bundle/PrivacyInfo.xcprivacy"
cat > "$privacy_bundle/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>fun.yunle.cook.wechat-sdk-privacy</string>
  <key>CFBundlePackageType</key><string>BNDL</string>
  <key>CFBundleVersion</key><string>1</string>
</dict>
</plist>
PLIST
