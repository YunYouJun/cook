import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { spawn, spawnSync } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
// eslint-disable-next-line test/no-import-node-test -- CI orchestration must be checked before workspace dependencies are installed.
import { test } from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { planIosTests } from '../scripts/ci-changes.mjs'

const root = fileURLToPath(new URL('..', import.meta.url))
const fixtures = JSON.parse(readFileSync(new URL('./fixtures/ios-infrastructure-failures.json', import.meta.url), 'utf8'))

function workflowCommand() {
  const workflow = readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8')
  assert.match(workflow, /run: bash scripts\/run-ios-ci-tests.sh/)
  return readFileSync(join(root, 'scripts/run-ios-ci-tests.sh'), 'utf8')
}

// Replay only the external Xcode/simulator boundary. The actual checked-in CI
// shell and retry classifier execute unchanged; no local simulator is touched.
function replay(scenarios, verify, suites = ['regression-main', 'regression-discovery', 'dietary']) {
  const temporary = mkdtempSync(join(tmpdir(), 'cook-ios-ci-replay-'))
  const bin = join(temporary, 'bin')
  mkdirSync(bin)
  const callsPath = join(temporary, 'calls.jsonl')
  const outputPath = join(temporary, 'github-output')
  const scenarioPath = join(temporary, 'scenarios.json')
  writeFileSync(scenarioPath, JSON.stringify(scenarios))
  const writeCommand = (name, source) => {
    const path = join(bin, name)
    writeFileSync(path, `#!/usr/bin/env node\n${source}`)
    chmodSync(path, 0o755)
  }
  writeCommand('xcodebuild', `
    const fs = require('node:fs'), path = require('node:path');
    const args = process.argv.slice(2);
    const bundle = args[args.indexOf('-resultBundlePath') + 1];
    const name = path.basename(bundle, '.xcresult');
    const base = name.replace(/-infrastructure-retry$/, '');
    const log = process.env.COOK_IOS_CALLS;
    const calls = fs.existsSync(log) ? fs.readFileSync(log, 'utf8').trim().split('\\n').map(JSON.parse) : [];
    const attempt = calls.filter(call => call.base === base).length;
    const scenarios = JSON.parse(fs.readFileSync(process.env.COOK_IOS_SCENARIOS, 'utf8'));
    const summary = scenarios[base]?.[attempt] ?? { totalTestCount: 1, passedTests: 1, failedTests: 0, skippedTests: 0, testFailures: [] };
    fs.mkdirSync(bundle, { recursive: true });
    fs.writeFileSync(path.join(bundle, 'summary.json'), JSON.stringify(summary));
    fs.appendFileSync(log, JSON.stringify({ name, base, args, failedTests: summary.failedTests }) + '\\n');
    process.exitCode = summary.failedTests > 0 ? 65 : 0;
  `)
  writeCommand('xcrun', `
    const fs = require('node:fs'), path = require('node:path');
    const args = process.argv.slice(2);
    if (args[0] === 'xcresulttool') {
      const bundle = args[args.indexOf('--path') + 1];
      process.stdout.write(fs.readFileSync(path.join(bundle, 'summary.json'), 'utf8'));
    }
  `)
  try {
    const runs = ['phone', 'pad'].flatMap(device => suites.map(suite => spawnSync('bash', ['-e', '-c', workflowCommand()], {
      cwd: root,
      encoding: 'utf8',
      timeout: 15_000,
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        RUNNER_TEMP: temporary,
        GITHUB_OUTPUT: outputPath,
        GITHUB_STEP_SUMMARY: join(temporary, 'step-summary'),
        DEVICE: device,
        IOS_SUITE: suite,
        COOK_IOS_CALLS: callsPath,
        COOK_IOS_SCENARIOS: scenarioPath,
      },
    })))
    for (const run of runs) assert.equal(run.error, undefined)
    const result = { status: runs.some(run => run.status !== 0) ? 1 : 0, stderr: runs.map(run => run.stderr).join('\n') }
    const calls = existsSync(callsPath) ? readFileSync(callsPath, 'utf8').trim().split('\n').map(JSON.parse) : []
    const summary = name => JSON.parse(readFileSync(join(temporary, 'xcresult', `${name}.xcresult`, 'summary.json'), 'utf8'))
    verify({ result, calls, summary, output: existsSync(outputPath) ? readFileSync(outputPath, 'utf8') : '' })
  }
  finally {
    rmSync(temporary, { recursive: true, force: true })
  }
}

test('a slow Actions log consumer cannot block xcodebuild or hide its failure', async () => {
  const temporary = mkdtempSync(join(tmpdir(), 'cook-ios-ci-log-'))
  const bin = join(temporary, 'bin')
  mkdirSync(bin)
  const completed = join(temporary, 'xcode-completed')
  const failure = '\nXCODE_FAILURE_EVIDENCE\n'
  writeFileSync(join(bin, 'xcrun'), '#!/usr/bin/env bash\nexit 0\n')
  writeFileSync(join(bin, 'xcodebuild'), `#!/usr/bin/env node
    const fs = require('node:fs');
    process.stdout.write('x'.repeat(1024 * 1024) + ${JSON.stringify(failure)}, () => {
      fs.writeFileSync(process.env.COOK_XCODE_COMPLETED, 'done');
      process.exitCode = 65;
    });
  `)
  for (const command of ['xcrun', 'xcodebuild']) chmodSync(join(bin, command), 0o755)
  const child = spawn('bash', ['-e', '-c', workflowCommand()], {
    cwd: root,
    detached: true,
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      RUNNER_TEMP: temporary,
      DEVICE: 'phone',
      IOS_SUITE: 'core-regression',
      COOK_XCODE_COMPLETED: completed,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  const closed = new Promise((resolve, reject) => {
    child.once('error', reject)
    child.once('close', code => resolve(code))
  })
  child.stderr.resume()
  try {
    // Deliberately leave the consumer paused while the external test tool fills
    // more than a pipe buffer. Its own output must still finish before we drain.
    const deadline = Date.now() + 5000
    while (!existsSync(completed) && Date.now() < deadline) await delay(25)
    assert.ok(existsSync(completed), 'xcodebuild must finish even when Actions stops draining stdout')
    const chunks = []
    child.stdout.on('data', chunk => chunks.push(chunk))
    assert.equal(await closed, 1, 'the original test failure must fail the job')
    assert.ok(Buffer.concat(chunks).toString().endsWith(failure), 'the full failure log must reach Actions afterward')
    assert.ok(readFileSync(join(temporary, 'xcresult/phone-core-regression.log'), 'utf8').endsWith(failure))
  }
  finally {
    child.stdout.resume()
    if (child.exitCode === null && child.signalCode === null)
      process.kill(-child.pid, 'SIGTERM')
    await closed
    rmSync(temporary, { recursive: true, force: true })
  }
})

test('the matrix preserves serial invocations and uses compiled products on existing devices', () => {
  replay({}, ({ result, calls, output }) => {
    assert.equal(result.status, 0, result.stderr)
    assert.equal(calls.length, 10)
    assert.deepEqual(calls.map(call => call.name), ['phone', 'pad'].flatMap(device => ['regression-main', 'regression-discovery', 'dietary-standard', 'dietary-maximum', 'dietary-regression'].map(stage => `${device}-${stage}`)))
    for (const { args } of calls) {
      assert.equal(args[0], 'test-without-building')
      assert.equal(args[args.indexOf('-test-timeouts-enabled') + 1], 'YES')
      assert.equal(args[args.indexOf('-parallel-testing-enabled') + 1], 'NO')
      for (const argument of args.filter(value => /^-(?:only|skip)-testing:/.test(value))) {
        const [target, suite, method] = argument.split(':')[1].split('/')
        assert.equal(target, 'CookUITests')
        const source = readFileSync(join(root, 'apps/ios/CookUITests', `${suite}.swift`), 'utf8')
        if (method)
          assert.match(source, new RegExp(`\\bfunc\\s+${method}\\s*\\(`), `CI selector must name an existing test: ${argument}`)
      }
    }
    assert.equal(output, '')
  })
})

test('a captured audit-service timeout repeats only that isolated case and preserves both results', () => {
  replay({ 'pad-dietary-standard': [fixtures.auditTimeout] }, ({ result, calls, summary, output }) => {
    assert.equal(result.status, 0, result.stderr)
    assert.equal(calls.length, 11)
    assert.equal(summary('pad-dietary-standard').failedTests, 1)
    assert.equal(summary('pad-dietary-standard-infrastructure-retry').passedTests, 1)
    assert.match(output, /infrastructure_retries=true/)
    assert.deepEqual(calls.filter(call => call.base === 'pad-dietary-standard').map(call => call.args.filter(arg => arg.startsWith('-only-testing:'))), Array.from({ length: 2 }, () => ['-only-testing:CookUITests/DietaryGoalsUITests/testNativeGoalPickerAccessibilityAndReviewedSummary']))
  })
})

test('a captured runner bootstrap stall repeats only the isolated maximum-size case', () => {
  replay({ 'pad-dietary-maximum': [fixtures.bootstrap] }, ({ result, calls, summary }) => {
    assert.equal(result.status, 0, result.stderr)
    assert.equal(calls.length, 11)
    assert.equal(summary('pad-dietary-maximum').failedTests, 1)
    assert.equal(summary('pad-dietary-maximum-infrastructure-retry').passedTests, 1)
  })
})

test('an infrastructure error that repeats still fails after one retry', () => {
  replay({ 'pad-dietary-standard': [fixtures.auditTimeout, fixtures.auditTimeout] }, ({ result, calls, summary }) => {
    assert.equal(result.status, 1)
    assert.equal(calls.filter(call => call.base === 'pad-dietary-standard').length, 2)
    assert.equal(summary('pad-dietary-standard').failedTests, 1)
    assert.equal(summary('pad-dietary-standard-infrastructure-retry').failedTests, 1)
  })
})

test('the captured keyboard assertion never retries the regular regression', () => {
  replay({ 'phone-regression-main': [fixtures.keyboardAssertion] }, ({ result, calls, output }) => {
    assert.equal(result.status, 1)
    assert.equal(calls.length, 10)
    assert.equal(output, '')
  })
})

test('a later successful infrastructure retry cannot erase an earlier business failure', () => {
  replay({ 'phone-regression-main': [fixtures.keyboardAssertion], 'pad-dietary-standard': [fixtures.auditTimeout] }, ({ result, calls, summary, output }) => {
    assert.equal(result.status, 1)
    assert.equal(calls.length, 11)
    assert.equal(summary('phone-regression-main').failedTests, 1)
    assert.equal(summary('pad-dietary-standard-infrastructure-retry').passedTests, 1)
    assert.match(output, /infrastructure_retries=true/)
  })
})

test('business assertions, real audit findings, skipped tests and mixed failures do not retry', () => {
  for (const change of [
    (summary) => { summary.testFailures[0].failureText = 'XCTAssertTrue failed - native memory switch did not change' },
    (summary) => { summary.testFailures[0].failureText = 'Accessibility audit found a contrast issue' },
    (summary) => { summary.skippedTests = 1 },
    (summary) => { summary.expectedFailures = 1 },
    (summary) => { summary.testFailures[0].failureText = summary.testFailures[0].failureText.replace('Code=-56', 'Code=-99') },
    (summary) => { summary.testFailures.push({ ...summary.testFailures[0], failureText: 'XCTAssertEqual failed' }) },
    (summary) => { summary.testFailures[0].testIdentifierString = 'OtherUITests/testOther()' },
  ]) {
    const failure = structuredClone(fixtures.auditTimeout)
    change(failure)
    replay({ 'pad-dietary-standard': [failure] }, ({ result, calls, output }) => {
      assert.equal(result.status, 1)
      assert.equal(calls.length, 10)
      assert.equal(output, '')
    })
  }
})

test('pushes and PRs use core coverage while manual and unknown requests retain full coverage', () => {
  const core = { suites: ['core-regression'], maxParallel: 2 }
  const full = { suites: ['regression-main', 'regression-discovery', 'dietary'], maxParallel: 3 }
  for (const event of ['push', 'pull_request']) {
    assert.deepEqual(planIosTests(event), core)
    assert.deepEqual(planIosTests(event, 'full'), core)
  }
  assert.deepEqual(planIosTests('workflow_dispatch', 'core'), core)
  for (const coverage of [undefined, '', 'full', 'unknown'])
    assert.deepEqual(planIosTests('workflow_dispatch', coverage), full)
  assert.deepEqual(planIosTests('unknown', 'core'), full)
})

test('the real change-detection command publishes the selected native matrix', () => {
  const temporary = mkdtempSync(join(tmpdir(), 'cook-ios-ci-plan-'))
  try {
    for (const coverage of ['full', 'core']) {
      const eventPath = join(temporary, `${coverage}.json`)
      const outputPath = join(temporary, `${coverage}.output`)
      writeFileSync(eventPath, JSON.stringify({ inputs: { ios_coverage: coverage } }))
      const result = spawnSync(process.execPath, ['scripts/ci-changes.mjs'], {
        cwd: root,
        encoding: 'utf8',
        timeout: 5000,
        env: {
          ...process.env,
          GITHUB_EVENT_NAME: 'workflow_dispatch',
          GITHUB_EVENT_PATH: eventPath,
          GITHUB_OUTPUT: outputPath,
          GITHUB_STEP_SUMMARY: join(temporary, `${coverage}.summary`),
        },
      })
      assert.equal(result.status, 0, result.stderr)
      const output = Object.fromEntries(readFileSync(outputPath, 'utf8').trim().split('\n').map((line) => {
        const separator = line.indexOf('=')
        return [line.slice(0, separator), line.slice(separator + 1)]
      }))
      const plan = planIosTests('workflow_dispatch', coverage)
      assert.equal(output.skip_native, 'false', 'an explicit manual run must execute native tests')
      assert.deepEqual(JSON.parse(output.ios_suites), plan.suites)
      assert.equal(Number(output.ios_max_parallel), plan.maxParallel)
    }
  }
  finally {
    rmSync(temporary, { recursive: true, force: true })
  }
})

test('native CI bounds build and test time and runs device groups independently', () => {
  const workflow = readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8')
  assert.match(workflow, /device: \[iphone, ipad\]/)
  const suites = workflow.match(/suite: \$\{\{ fromJSON\(needs.changes.outputs.ios_suites \|\| '([^']+)'\) \}\}/)
  assert.ok(suites, 'the matrix uses the tested coverage plan with a full-coverage fallback')
  assert.deepEqual(JSON.parse(suites[1]), ['regression-main', 'regression-discovery', 'dietary'])
  const nativeJob = workflow.slice(workflow.indexOf('\n  ios-tests:\n'), workflow.indexOf('\n  ios:\n'))
  assert.match(nativeJob, /max-parallel: \$\{\{ fromJSON\(needs.changes.outputs.ios_max_parallel \|\| '3'\) \}\}/)
  assert.match(workflow, /\n {2}workflow_dispatch:/)
  assert.match(workflow, /name: Build for testing\n\s+timeout-minutes: 15/)
  assert.match(workflow, /name: Build and test\n\s+timeout-minutes: \$\{\{ matrix.suite == 'core-regression' && 30 \|\| 60 \}\}/)
  assert.match(nativeJob, /timeout-minutes: \$\{\{ matrix.suite == 'core-regression' && 45 \|\| 90 \}\}/)
  assert.match(workflow, /ios_suites: \$\{\{ steps.changes.outputs.ios_suites \}\}/)
  assert.match(workflow, /ios_coverage:[\s\S]*?default: full/)
  assert.match(workflow, /group: ci-.*github\.event_name == 'workflow_dispatch' && inputs\.ios_coverage != 'core' && 'full' \|\| 'core'/)
  assert.match(workflow, /cancel-in-progress: true/)
})

test('the stable ios gate rejects failed, cancelled and unexpectedly skipped matrices', () => {
  const workflow = readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8')
  const gate = workflow.slice(workflow.indexOf('\n  ios:\n'))
  const command = gate.split('        run: |\n')[1].split('\n').map(line => line.slice(10)).join('\n')
  for (const [changes, skipNative, ios, success] of [
    ['success', 'false', 'success', true],
    ['success', 'true', 'skipped', true],
    ['success', 'false', 'failure', false],
    ['success', 'false', 'cancelled', false],
    ['success', 'false', 'skipped', false],
    ['failure', 'true', 'skipped', false],
    ['success', 'true', 'cancelled', false],
  ]) {
    const result = spawnSync('bash', ['-e', '-c', command], {
      env: { ...process.env, CHANGES_RESULT: changes, SKIP_NATIVE: skipNative, IOS_RESULT: ios },
    })
    assert.equal(result.status === 0, success, `${changes}/${skipNative}/${ios}`)
  }
})

test('every UI method is assigned exactly once per device, including both isolated audits', () => {
  replay({}, ({ calls }) => {
    const directory = join(root, 'apps/ios/CookUITests')
    for (const file of readdirSync(directory).filter(file => file.endsWith('.swift'))) {
      const source = readFileSync(join(directory, file), 'utf8')
      const suite = source.match(/final class (\w+): XCTestCase/)?.[1]
      if (!suite)
        continue
      for (const match of source.matchAll(/func (test\w+)\(/g)) {
        const identifier = `CookUITests/${suite}/${match[1]}`
        const matches = selector => identifier === selector || identifier.startsWith(`${selector}/`)
        for (const device of ['phone', 'pad']) {
          const selected = calls.filter(call => call.name.startsWith(`${device}-`)).filter(({ args }) => {
            const only = args.filter(arg => arg.startsWith('-only-testing:')).map(arg => arg.slice(14))
            const skip = args.filter(arg => arg.startsWith('-skip-testing:')).map(arg => arg.slice(14))
            return (!only.length || only.some(matches)) && !skip.some(matches)
          })
          assert.equal(selected.length, 1, `${device}: ${identifier} must run exactly once`)
        }
      }
    }
  })
})

test('core regression retains all App units and five UI flows including the current failure regressions', () => {
  replay({}, ({ result, calls, output }) => {
    assert.equal(result.status, 0, result.stderr)
    assert.deepEqual(calls.map(call => call.name), ['phone-core-regression', 'pad-core-regression'])
    const required = [
      'CookUITests/testAvoidPickerSearchKeepsSelectionsAvailableForRemoval',
      'CookDiscoveryUITests/testDefaultCatalogCountMatchesFilterPreviewWithoutIngredients',
      'CloudKitchenUITests/testOptionalRotationMovesCameraKeepsTapsAndResetsWhenDisabled',
      'DietaryGoalsUITests/testNextDayLunchAndWeightGoalAtAccessibilitySize',
      'DietaryGoalsUITests/testClearingGoalFromResultsKeepsPickerPresentedUntilDone',
    ].map(test => `CookUITests/${test}`)
    const fullOnly = [
      'CookUITests/testCookFlowByAccessibilityLabels',
      'CookUITests/testPantryShelfRemembersChoicesAndRestoresDefaults',
      'CloudKitchenUITests/testKitchenAndListShareSelectionAndPotOpensRealRecipes',
      'RandomRecipeUITests/testLargestTextRandomSharesDietaryGoalAndPreservesEmptyResults',
      'DietaryGoalsUITests/testNextDayLunchAndWeightGoalIntersectAndShowSourceOilUnits',
      'DietaryGoalsUITests/testGoalEntryStaysInFiltersAcrossLaunchesAndSelectionRemainsVisible',
      'DietaryGoalsUITests/testEmptyGoalCanBeCancelledWithoutLosingSelectedIngredients',
      'DietaryGoalsUITests/testGoalSelectionIsSavedAndUpdatedInFiltersAcrossLaunches',
      'DietaryGoalsUITests/testGoalBrowsingOpensReviewedTextAndPreservesGoalWhenDeciding',
      'DietaryGoalsUITests/testCloudKitchenKeepsGoalAndCanBrowseWithoutIngredients',
      'DietaryGoalsUITests/testNativeGoalPickerAccessibilityAndReviewedSummary',
      'DietaryGoalsUITests/testLargestTextCanChooseSavedGoalReadAndDecide',
    ].map(test => `CookUITests/${test}`)
    for (const { args } of calls) {
      const only = args.filter(arg => arg.startsWith('-only-testing:')).map(arg => arg.slice(14))
      const skip = args.filter(arg => arg.startsWith('-skip-testing:')).map(arg => arg.slice(14))
      const selected = test => only.some(selector => test === selector || test.startsWith(`${selector}/`))
        && !skip.some(selector => test === selector || test.startsWith(`${selector}/`))
      assert.ok(only.includes('CookTests'), 'the complete App unit-test target must run')
      assert.ok(!skip.some(selector => selector.startsWith('CookTests')), 'App unit tests must not be excluded')
      for (const test of required) assert.ok(selected(test), `${test} must run on both devices`)
      for (const test of fullOnly)
        assert.ok(!selected(test), `${test} remains in the manual full matrix`)
      assert.equal(only.filter(selector => selector.startsWith('CookUITests/')).length, 5)
      for (const selector of only.filter(selector => selector.startsWith('CookUITests/'))) {
        const [, suite, method] = selector.split('/')
        const source = readFileSync(join(root, 'apps/ios/CookUITests', `${suite}.swift`), 'utf8')
        if (method)
          assert.match(source, new RegExp(`\\bfunc\\s+${method}\\s*\\(`))
      }
    }
    assert.equal(output, '')
  }, ['core-regression'])
})

test('a core assertion failure is not retried or hidden by the other device', () => {
  replay({ 'pad-core-regression': [fixtures.keyboardAssertion] }, ({ result, calls, output }) => {
    assert.equal(result.status, 1)
    assert.equal(calls.length, 2)
    assert.equal(output, '')
  }, ['core-regression'])
})
