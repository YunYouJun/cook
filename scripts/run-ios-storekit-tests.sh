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
  -destination 'generic/platform=iOS Simulator' -parallel-testing-enabled NO \
  -onlyUsePackageVersionsFromResolvedFile -skipPackageUpdates \
  -derivedDataPath "$RUNNER_TEMP/cook-derived-data" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- || code=$?
[[ "$code" == 0 ]] || exit "$code"
xcrun simctl bootstatus "$DEVICE" -b
run_stage() {
  local stage="$1" stage_code=0
  local test_log="$RUNNER_TEMP/xcresult/$DEVICE-$stage.log"
  run_logged "$test_log" \
    xcodebuild test-without-building -project apps/ios/Cook.xcodeproj -scheme CookMembershipStoreKit \
    -only-testing:CookUITests/MembershipStoreKitUITests \
    -only-testing:CookStoreKitTests \
    -destination "id=$DEVICE" -destination-timeout 180 -parallel-testing-enabled NO \
    -derivedDataPath "$RUNNER_TEMP/cook-derived-data" -collect-test-diagnostics never \
    -test-timeouts-enabled YES -default-test-execution-time-allowance 120 -maximum-test-execution-time-allowance 120 \
    -resultBundlePath "$RUNNER_TEMP/xcresult/$DEVICE-$stage.xcresult" \
    CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- || stage_code=$?
  if [[ "$code" == 0 && "$stage_code" != 0 ]]; then code=$stage_code; fi
}
# The scheme orders the cold purchase scene before the headless SDK host.
# Keep both targets in one Xcode operation to avoid a second destination /
# test-manager bootstrap. Every financial case executes once; any failure fails.
run_stage storekit
exit "$code"
