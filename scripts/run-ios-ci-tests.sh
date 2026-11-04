#!/usr/bin/env bash
set -euo pipefail

xcrun simctl list runtimes
xcrun simctl list devices available
# Each matrix cell uses one existing device, with serial tests and bounded retries.
# Build once in the workflow; every invocation reuses those test products.
result=0
standard_audit=CookUITests/DietaryGoalsUITests/testNativeGoalPickerAccessibilityAndReviewedSummary
maximum_audit=CookUITests/DietaryGoalsUITests/testLargestTextCanChooseSavedGoalReadAndDecide
run_once() {
  local device="$1" stage="$2" code=0 allowance=300
  shift 2
  local log="$RUNNER_TEMP/xcresult/$device-$stage.log"
  mkdir -p "$RUNNER_TEMP/xcresult"
  [[ "$stage" == dietary-standard* || "$stage" == dietary-maximum* ]] && allowance=600
  echo "Running $stage on $device"
  if xcrun simctl bootstatus "$device" -b; then
    # Keep Actions console backpressure out of the Xcode / App logging path.
    # Replay the complete output after testing; retain it with the result bundle.
    echo "Capturing xcodebuild output in $log; full output follows when tests finish."
    xcodebuild test-without-building -project apps/ios/Cook.xcodeproj -scheme Cook -destination "id=$device" \
      -destination-timeout 180 -parallel-testing-enabled NO \
      -derivedDataPath "$RUNNER_TEMP/cook-derived-data" \
      -collect-test-diagnostics never \
      -test-timeouts-enabled YES -default-test-execution-time-allowance "$allowance" -maximum-test-execution-time-allowance 600 \
      "$@" -resultBundlePath "$RUNNER_TEMP/xcresult/$device-$stage.xcresult" \
      CODE_SIGNING_ALLOWED=NO > "$log" 2>&1 || code=$?
    cat "$log" || code=1
  else
    code=1
  fi
  xcrun simctl shutdown "$device" || result=1
  return "$code"
}
run_tests() {
  local device="$1" stage="$2"
  shift 2
  if run_once "$device" "$stage" "$@"; then
    return
  fi
  # Keep the failed bundle. Only these two known audit-infrastructure
  # errors may repeat once in a new host on the same existing device.
  if [[ "$stage" == dietary-standard || "$stage" == dietary-maximum ]] &&
    node scripts/classify-ios-test-retry.mjs "$RUNNER_TEMP/xcresult/$device-$stage.xcresult" "${1-}"; then
    echo "::warning title=Isolated iOS infrastructure retry::$device $stage failed before audit completion; original result bundle retained"
    echo "infrastructure_retries=true" >> "$GITHUB_OUTPUT"
    run_once "$device" "$stage-infrastructure-retry" "$@" || result=1
  else
    result=1
  fi
}
case "$IOS_SUITE" in
  pr-regression)
    # All App units plus ten UI gates on each device. Keep both lunch text sizes
    # and the recent failure regression; repeated long journeys run in the full matrix.
    run_tests "$DEVICE" pr-regression \
      -only-testing:CookTests \
      -only-testing:CookUITests/DietaryGoalsUITests/testNextDayLunchAndWeightGoalIntersectAndShowSourceOilUnits \
      -only-testing:CookUITests/DietaryGoalsUITests/testNextDayLunchAndWeightGoalAtAccessibilitySize \
      -only-testing:CookUITests/DietaryGoalsUITests/testGoalEntryStaysInFiltersAcrossLaunchesAndSelectionRemainsVisible \
      -only-testing:CookUITests/DietaryGoalsUITests/testEmptyGoalCanBeCancelledWithoutLosingSelectedIngredients \
      -only-testing:CookUITests/DietaryGoalsUITests/testClearingGoalFromResultsKeepsPickerPresentedUntilDone \
      -only-testing:CookUITests/CookUITests/testAvoidPickerSearchKeepsSelectionsAvailableForRemoval \
      -only-testing:CookUITests/CookUITests/testPantryShelfRemembersChoicesAndRestoresDefaults \
      -only-testing:CookUITests/CookDiscoveryUITests/testDefaultCatalogCountMatchesFilterPreviewWithoutIngredients \
      -only-testing:CookUITests/CloudKitchenUITests/testKitchenAndListShareSelectionAndPotOpensRealRecipes \
      -only-testing:CookUITests/RandomRecipeUITests/testLargestTextRandomSharesDietaryGoalAndPreservesEmptyResults
    ;;
  regression-main)
    # Everything else, including all App unit tests and newly added UI suites.
    run_tests "$DEVICE" regression-main \
      -skip-testing:CookUITests/CloudKitchenUITests \
      -skip-testing:CookUITests/CookDiscoveryUITests \
      -skip-testing:CookUITests/RandomRecipeUITests \
      -skip-testing:CookUITests/DietaryGoalsUITests
    ;;
  regression-discovery)
    run_tests "$DEVICE" regression-discovery \
      -only-testing:CookUITests/CloudKitchenUITests \
      -only-testing:CookUITests/CookDiscoveryUITests \
      -only-testing:CookUITests/RandomRecipeUITests
    ;;
  dietary)
    # Audits start on a fresh runner, before the regular UI regression.
    run_tests "$DEVICE" dietary-standard "-only-testing:$standard_audit"
    run_tests "$DEVICE" dietary-maximum "-only-testing:$maximum_audit"
    run_tests "$DEVICE" dietary-regression -only-testing:CookUITests/DietaryGoalsUITests \
      "-skip-testing:$standard_audit" "-skip-testing:$maximum_audit"
    ;;
  *) echo "Unknown iOS suite: $IOS_SUITE" >&2; exit 2 ;;
esac
exit "$result"
