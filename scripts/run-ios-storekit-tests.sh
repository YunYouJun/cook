#!/usr/bin/env bash
set -euo pipefail
: "${DEVICE:?Select an existing compatible simulator}"
: "${RUNNER_TEMP:?Use a task-owned temporary directory}"

# Local StoreKit is deliberately isolated from Cook's normal/Release scheme.
# Ad-hoc simulator signing needs no Apple development or distribution identity.
mkdir -p "$RUNNER_TEMP/xcresult"
build_log="$RUNNER_TEMP/xcresult/$DEVICE-storekit-build.log"
trap 'xcrun simctl shutdown "$DEVICE" >/dev/null 2>&1 || true' EXIT
active_command=''
stop_job() {
  trap '' INT TERM
  if [[ -n "$active_command" ]]; then
    kill -TERM "$active_command" 2>/dev/null || true
    wait "$active_command" || true
  fi
  exit "$1"
}
trap 'stop_job 130' INT
trap 'stop_job 143' TERM
run_logged() {
  local command_code=0
  python3 scripts/run-ios-test-command.py "$@" &
  active_command=$!
  wait "$active_command" || command_code=$?
  active_command=''
  return "$command_code"
}
code=0
run_logged "$build_log" \
  xcodebuild build-for-testing -project apps/ios/Cook.xcodeproj -scheme CookMembershipStoreKit \
  -testPlan CookMembershipStoreKit \
  -destination 'generic/platform=iOS Simulator' -parallel-testing-enabled NO \
  -onlyUsePackageVersionsFromResolvedFile -skipPackageUpdates \
  -derivedDataPath "$RUNNER_TEMP/cook-derived-data" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- || code=$?
[[ "$code" == 0 ]] || exit "$code"
xcrun simctl bootstatus "$DEVICE" -b
xcrun simctl spawn "$DEVICE" launchctl kickstart -k user/501/com.apple.storekitd
for attempt in {1..30}; do
  state=$(xcrun simctl spawn "$DEVICE" launchctl print user/501/com.apple.storekitd 2>/dev/null \
    | sed -n 's/^[[:space:]]*state = //p' | head -1)
  [[ "$state" == running ]] && break
  if [[ "$attempt" == 30 ]]; then
    echo "StoreKit test service did not start" >&2
    exit 1
  fi
  sleep 1
done
run_stage() {
  local stage="$1" stage_code=0
  local test_log="$RUNNER_TEMP/xcresult/$DEVICE-$stage.log"
  run_logged "$test_log" \
    xcodebuild test-without-building -project apps/ios/Cook.xcodeproj -scheme CookMembershipStoreKit \
    -testPlan CookMembershipStoreKit \
    -only-testing:CookStoreKitTests \
    -only-testing:CookUITests/MembershipStoreKitUITests \
    -destination "id=$DEVICE" -destination-timeout 180 -parallel-testing-enabled NO \
    -derivedDataPath "$RUNNER_TEMP/cook-derived-data" -collect-test-diagnostics never \
    -test-timeouts-enabled YES -default-test-execution-time-allowance 120 -maximum-test-execution-time-allowance 120 \
    -resultBundlePath "$RUNNER_TEMP/xcresult/$DEVICE-$stage.xcresult" \
    CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- || stage_code=$?
  if [[ "$code" == 0 && "$stage_code" != 0 ]]; then code=$stage_code; fi
}
# Warm the SDK host before the first purchase scene. Xcode 27 can leave a
# cold StoreKit service unavailable to the first UI runner. Keep both targets
# in one operation so the service initialization precedes Apple's real sheet.
run_stage storekit
exit "$code"
