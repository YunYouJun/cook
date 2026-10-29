import { execFileSync } from 'node:child_process'
import { resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const isolatedAudits = new Set([
  'CookUITests/DietaryGoalsUITests/testNativeGoalPickerAccessibilityAndReviewedSummary',
  'CookUITests/DietaryGoalsUITests/testLargestTextCanChooseRememberReadAndRoll',
])

// Only a captured infrastructure failure of one isolated audit may be retried.
// Assertions, audit findings, skipped/multiple tests and unknown formats fail closed.
export function classifyIsolatedAuditFailure(summary, argument) {
  const selector = typeof argument === 'string' ? argument.replace(/^-only-testing:/, '') : ''
  if (!isolatedAudits.has(selector))
    return { eligible: false, reason: 'not-an-isolated-dietary-audit' }
  if (summary?.totalTestCount !== 1 || summary?.failedTests !== 1 || summary?.passedTests !== 0
    || summary?.skippedTests !== 0 || (summary?.expectedFailures ?? 0) !== 0
    || !Array.isArray(summary?.testFailures) || summary.testFailures.length !== 1) {
    return { eligible: false, reason: 'not-one-unskipped-failed-audit' }
  }

  const failure = summary.testFailures[0]
  if (failure?.targetName !== 'CookUITests' || typeof failure.failureText !== 'string')
    return { eligible: false, reason: 'not-a-ui-test-failure' }
  const text = failure.failureText
  const identifier = `${selector.split('/').slice(1).join('/')}()`
  if (failure.testIdentifierString === identifier
    && text.startsWith('failed: caught error: "Error Domain=com.apple.xcode.xctest.accessibilityAudit Code=-56 ')
    && text.includes('"Audit failed to complete in time"')) {
    return { eligible: true, reason: 'audit-service-timeout' }
  }
  if (/^CookUITests-Runner \(\d+\) encountered an error$/.test(failure.testIdentifierString)
    && text.startsWith('Early unexpected exit, operation never finished bootstrapping')
    && text.includes('test runner crashed while preparing to run tests')
    && text.includes('handleStalledWait')) {
    return { eligible: true, reason: 'runner-bootstrap-stall' }
  }
  return { eligible: false, reason: 'test-failure-not-retryable' }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === resolve(process.argv[1])) {
  let decision
  try {
    const raw = execFileSync('xcrun', ['xcresulttool', 'get', 'test-results', 'summary', '--path', process.argv[2]], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      maxBuffer: 2 * 1024 * 1024,
      timeout: 30_000,
    })
    decision = classifyIsolatedAuditFailure(JSON.parse(raw), process.argv[3])
  }
  catch {
    decision = { eligible: false, reason: 'unreadable-result-summary' }
  }
  console.log(JSON.stringify(decision))
  process.exitCode = decision.eligible ? 0 : 1
}
