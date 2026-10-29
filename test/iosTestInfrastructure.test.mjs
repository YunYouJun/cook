import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
// eslint-disable-next-line test/no-import-node-test -- CI orchestration must be checked before workspace dependencies are installed.
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const fixtures = JSON.parse(readFileSync(new URL('./fixtures/ios-infrastructure-failures.json', import.meta.url), 'utf8'))

function workflowCommand() {
  const lines = readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8').split('\n')
  const step = lines.findIndex(line => line.trim() === '- name: Build and test')
  assert.notEqual(step, -1)
  const start = lines.findIndex((line, index) => index > step && line === '        run: |') + 1
  assert.ok(start > step)
  const commands = []
  for (const line of lines.slice(start)) {
    if (line && !line.startsWith('          '))
      break
    commands.push(line.slice(10))
  }
  return commands.join('\n')
}

// Replay only the external Xcode/simulator boundary. The actual checked-in CI
// shell and retry classifier execute unchanged; no local simulator is touched.
function replay(scenarios, verify) {
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
    const result = spawnSync('bash', ['-e', '-c', workflowCommand()], {
      cwd: root,
      encoding: 'utf8',
      timeout: 15_000,
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        RUNNER_TEMP: temporary,
        GITHUB_OUTPUT: outputPath,
        GITHUB_STEP_SUMMARY: join(temporary, 'step-summary'),
        IPHONE: 'phone',
        IPAD: 'pad',
        COOK_IOS_CALLS: callsPath,
        COOK_IOS_SCENARIOS: scenarioPath,
      },
    })
    assert.equal(result.error, undefined)
    const calls = readFileSync(callsPath, 'utf8').trim().split('\n').map(JSON.parse)
    const summary = name => JSON.parse(readFileSync(join(temporary, 'xcresult', `${name}.xcresult`, 'summary.json'), 'utf8'))
    verify({ result, calls, summary, output: existsSync(outputPath) ? readFileSync(outputPath, 'utf8') : '' })
  }
  finally {
    rmSync(temporary, { recursive: true, force: true })
  }
}

test('the original six serial test invocations remain unchanged when successful', () => {
  replay({}, ({ result, calls, output }) => {
    assert.equal(result.status, 0, result.stderr)
    assert.equal(calls.length, 6)
    assert.deepEqual(calls.map(call => call.name), ['phone-regression', 'phone-dietary-standard', 'phone-dietary-maximum', 'pad-regression', 'pad-dietary-standard', 'pad-dietary-maximum'])
    for (const { args } of calls)
      assert.equal(args[args.indexOf('-parallel-testing-enabled') + 1], 'NO')
    assert.equal(output, '')
  })
})

test('a captured audit-service timeout repeats only that isolated case and preserves both results', () => {
  replay({ 'pad-dietary-standard': [fixtures.auditTimeout] }, ({ result, calls, summary, output }) => {
    assert.equal(result.status, 0, result.stderr)
    assert.equal(calls.length, 7)
    assert.equal(summary('pad-dietary-standard').failedTests, 1)
    assert.equal(summary('pad-dietary-standard-infrastructure-retry').passedTests, 1)
    assert.match(output, /infrastructure_retries=true/)
    assert.deepEqual(calls.filter(call => call.base === 'pad-dietary-standard').map(call => call.args.filter(arg => arg.startsWith('-only-testing:'))), Array.from({ length: 2 }, () => ['-only-testing:CookUITests/DietaryGoalsUITests/testNativeGoalPickerAccessibilityAndReviewedSummary']))
  })
})

test('a captured runner bootstrap stall repeats only the isolated maximum-size case', () => {
  replay({ 'pad-dietary-maximum': [fixtures.bootstrap] }, ({ result, calls, summary }) => {
    assert.equal(result.status, 0, result.stderr)
    assert.equal(calls.length, 7)
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
  replay({ 'phone-regression': [fixtures.keyboardAssertion] }, ({ result, calls, output }) => {
    assert.equal(result.status, 1)
    assert.equal(calls.length, 6)
    assert.equal(output, '')
  })
})

test('a later successful infrastructure retry cannot erase an earlier business failure', () => {
  replay({ 'phone-regression': [fixtures.keyboardAssertion], 'pad-dietary-standard': [fixtures.auditTimeout] }, ({ result, calls, summary, output }) => {
    assert.equal(result.status, 1)
    assert.equal(calls.length, 7)
    assert.equal(summary('phone-regression').failedTests, 1)
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
      assert.equal(calls.length, 6)
      assert.equal(output, '')
    })
  }
})
