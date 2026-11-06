#!/usr/bin/env bash
set -euo pipefail
: "${DEVICE:?Select an existing compatible simulator}"
: "${RUNNER_TEMP:?Use a task-owned temporary directory}"

# Local StoreKit is deliberately isolated from Cook's normal/Release scheme.
# Ad-hoc simulator signing needs no Apple development or distribution identity.
mkdir -p "$RUNNER_TEMP/xcresult"
build_log="$RUNNER_TEMP/xcresult/$DEVICE-storekit-build.log"
trap 'xcrun simctl shutdown "$DEVICE" >/dev/null 2>&1 || true' EXIT
code=0
xcodebuild build-for-testing -project apps/ios/Cook.xcodeproj -scheme CookMembershipStoreKit \
  -destination 'generic/platform=iOS Simulator' -parallel-testing-enabled NO \
  -onlyUsePackageVersionsFromResolvedFile -skipPackageUpdates \
  -derivedDataPath "$RUNNER_TEMP/cook-derived-data" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- > "$build_log" 2>&1 || code=$?
cat "$build_log"
[[ "$code" == 0 ]] || exit "$code"
xcrun simctl bootstatus "$DEVICE" -b
run_stage() {
  local stage="$1" selector="$2" stage_code=0
  local test_log="$RUNNER_TEMP/xcresult/$DEVICE-$stage.log"
  xcodebuild test-without-building -project apps/ios/Cook.xcodeproj -scheme CookMembershipStoreKit \
    "$selector" \
    -destination "id=$DEVICE" -destination-timeout 180 -parallel-testing-enabled NO \
    -derivedDataPath "$RUNNER_TEMP/cook-derived-data" -collect-test-diagnostics never \
    -test-timeouts-enabled YES -default-test-execution-time-allowance 120 -maximum-test-execution-time-allowance 120 \
    -resultBundlePath "$RUNNER_TEMP/xcresult/$DEVICE-$stage.xcresult" \
    CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- > "$test_log" 2>&1 || stage_code=$?
  cat "$test_log"
  if [[ "$code" == 0 && "$stage_code" != 0 ]]; then code=$stage_code; fi
}
# Exercise the real, cold purchase scene before the headless SDK host. This
# tests configuration initialization rather than relying on a failed SDK call
# to prepare it. Both stages execute once; either failure fails this step.
run_stage storekit-ui -only-testing:CookUITests/MembershipStoreKitUITests
run_stage storekit -only-testing:CookStoreKitTests
exit "$code"
