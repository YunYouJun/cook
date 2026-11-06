#!/usr/bin/env bash
set -euo pipefail
: "${DEVICE:?Select an existing compatible simulator}"
: "${RUNNER_TEMP:?Use a task-owned temporary directory}"

# Local StoreKit is deliberately isolated from Cook's normal/Release scheme.
# Ad-hoc simulator signing needs no Apple development or distribution identity.
mkdir -p "$RUNNER_TEMP/xcresult"
build_log="$RUNNER_TEMP/xcresult/$DEVICE-storekit-build.log"
test_log="$RUNNER_TEMP/xcresult/$DEVICE-storekit.log"
trap 'xcrun simctl shutdown "$DEVICE" >/dev/null 2>&1 || true' EXIT
code=0
xcodebuild build-for-testing -project apps/ios/Cook.xcodeproj -scheme CookMembershipStoreKit \
  -destination 'generic/platform=iOS Simulator' -parallel-testing-enabled NO \
  -derivedDataPath "$RUNNER_TEMP/cook-derived-data" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- > "$build_log" 2>&1 || code=$?
cat "$build_log"
[[ "$code" == 0 ]] || exit "$code"
xcrun simctl bootstatus "$DEVICE" -b
xcodebuild test-without-building -project apps/ios/Cook.xcodeproj -scheme CookMembershipStoreKit \
  -destination "id=$DEVICE" -destination-timeout 180 -parallel-testing-enabled NO \
  -derivedDataPath "$RUNNER_TEMP/cook-derived-data" -collect-test-diagnostics never \
  -test-timeouts-enabled YES -default-test-execution-time-allowance 120 -maximum-test-execution-time-allowance 120 \
  -resultBundlePath "$RUNNER_TEMP/xcresult/$DEVICE-storekit.xcresult" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- > "$test_log" 2>&1 || code=$?
cat "$test_log"
exit "$code"
