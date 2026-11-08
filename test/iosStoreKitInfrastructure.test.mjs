import assert from 'node:assert/strict'
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
// eslint-disable-next-line test/no-import-node-test -- Verify the actual financial CI shell before installing workspace packages.
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { planIosTests } from '../scripts/ci-changes.mjs'

const root = fileURLToPath(new URL('..', import.meta.url))
const hostArchitecture = spawnSync('uname', ['-m'], { encoding: 'utf8' }).stdout.trim()

test('a cancelled financial step stops its writer and replays partial logs before exit', { timeout: 20_000 }, async () => {
  for (const ignoresInterrupt of ['0', '1'])
    await checkCancellation(ignoresInterrupt)
})

async function checkCancellation(ignoresInterrupt) {
  const task = mkdtempSync(join(tmpdir(), 'cook-storekit-cancel-'))
  let run
  let commandPID
  let cleanupError
  try {
    const bin = join(task, 'bin')
    mkdirSync(bin)
    writeFileSync(join(bin, 'xcrun'), `#!/bin/sh
case "$*" in
  "simctl spawn existing-phone launchctl print user/501/com.apple.storekitd") echo "state = running" ;;
esac
exit 0
`, { mode: 0o755 })
    const descendant = `
const fs = require('node:fs')
process.on('SIGINT', () => {})
console.log('PARTIAL_FINANCIAL_STARTUP_LOG')
fs.writeFileSync(process.env.READY, JSON.stringify({ writer: process.pid, command: process.ppid }))
setInterval(() => {}, 1000)
`
    writeFileSync(join(bin, 'xcodebuild'), `#!/usr/bin/env node
const fs = require('node:fs')
const { spawn } = require('node:child_process')
const args = process.argv.slice(2)
fs.appendFileSync(process.env.CALLS, JSON.stringify(args) + '\\n')
if (args[0] === 'build-for-testing') process.exit(0)
if (process.env.IGNORE_MAIN === '1') process.on('SIGINT', () => {})
spawn(process.execPath, ['-e', ${JSON.stringify(descendant)}], { stdio: 'inherit' })
setInterval(() => {}, 1000)
`, { mode: 0o755 })
    const ready = join(task, 'ready')
    const callsPath = join(task, 'calls')
    run = spawn('bash', ['scripts/run-ios-storekit-tests.sh'], {
      cwd: root,
      env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, DEVICE: 'existing-phone', RUNNER_TEMP: task, CALLS: callsPath, READY: ready, IGNORE_MAIN: ignoresInterrupt },
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    let output = ''
    run.stdout.on('data', value => output += value)
    run.stderr.on('data', value => output += value)
    const completed = new Promise((resolve, reject) => {
      run.once('exit', (code, signal) => resolve({ code, signal }))
      run.once('error', reject)
    })
    for (let poll = 0; poll < 100 && !existsSync(ready); poll++) await new Promise(resolve => setTimeout(resolve, 50))
    assert.ok(existsSync(ready), 'The command must be writing before cancellation')
    const readyProcesses = JSON.parse(readFileSync(ready, 'utf8'))
    commandPID = readyProcesses.command
    run.kill('SIGTERM')
    assert.deepEqual(await completed, { code: 143, signal: null })
    assert.match(output, /PARTIAL_FINANCIAL_STARTUP_LOG/)
    assert.match(readFileSync(join(task, 'xcresult/existing-phone-storekit.log'), 'utf8'), /PARTIAL_FINANCIAL_STARTUP_LOG/)
    assert.throws(() => process.kill(commandPID, 0), { code: 'ESRCH' })
    const writer = spawnSync('ps', ['-o', 'stat=', '-p', String(readyProcesses.writer)], { encoding: 'utf8', timeout: 2000 })
    assert.equal(writer.error, undefined)
    assert.match(writer.stdout.trim(), /^(?:Z.*)?$/, 'A descendant must also be stopped, even if its parent exited first')
    commandPID = undefined
    const calls = readFileSync(callsPath, 'utf8').trim().split('\n').map(JSON.parse)
    assert.equal(calls.length, 2, 'Cancellation must not start another Xcode operation')
    assert.ok(calls[1].includes('-only-testing:CookUITests/MembershipStoreKitUITests'))
  }
  finally {
    if (commandPID) {
      try {
        process.kill(-commandPID, 'SIGKILL')
      }
      catch (error) {
        if (error.code !== 'ESRCH')
          cleanupError = error
      }
    }
    if (run && run.exitCode === null)
      run.kill('SIGKILL')
    rmSync(task, { recursive: true, force: true })
  }
  if (cleanupError)
    throw cleanupError
}

test('StoreKit checks both ordered targets in one operation and preserves either failure without retry', () => {
  const project = readFileSync(join(root, 'apps/ios/project.yml'), 'utf8')
  const script = readFileSync(join(root, 'scripts/run-ios-storekit-tests.sh'), 'utf8')
  const plan = JSON.parse(readFileSync(join(root, 'apps/ios/StoreKitValidation/CookMembershipStoreKit.xctestplan'), 'utf8'))
  assert.equal(plan.defaultOptions.storeKitConfiguration.identifier, 'StoreKitValidation/Fixtures/MembershipProducts.storekit')
  assert.deepEqual(plan.testTargets.map(({ target }) => target.name), ['CookStoreKitTests', 'CookUITests'])
  assert.ok(plan.testTargets.every(({ parallelizable }) => parallelizable === false))
  assert.match(project.slice(project.indexOf('\n  CookMembershipStoreKit:')), /testPlans:\n\s+- path: StoreKitValidation\/CookMembershipStoreKit\.xctestplan\n\s+defaultPlan: true/)
  assert.match(script, /xcrun simctl boot "\$DEVICE"[\s\S]*xcodebuild build-for-testing/)
  assert.match(script, /state=\$\(read_service_state\)[\s\S]*if \[\[ "\$state" == running \]\][\s\S]*launchctl kickstart -k user\/501\/com\.apple\.storekitd[\s\S]*run_stage storekit/)
  for (const [buildExit, uiExit, sdkExit, expectedCalls] of [[0, 0, 0, 2], [65, 0, 0, 1], [0, 65, 0, 2], [0, 0, 66, 2], [0, 65, 66, 2]]) {
    const task = mkdtempSync(join(tmpdir(), 'cook-storekit-ci-'))
    try {
      const bin = join(task, 'bin')
      mkdirSync(bin)
      writeFileSync(join(bin, 'xcrun'), `#!/bin/sh
printf 'xcrun %s\\n' "$*" >> "$ORDER"
case "$*" in
  "simctl spawn existing-phone launchctl print user/501/com.apple.storekitd") echo "state = running" ;;
esac
exit 0
`, { mode: 0o755 })
      writeFileSync(join(bin, 'xcodebuild'), `#!/usr/bin/env node
const fs = require('node:fs')
const args = process.argv.slice(2)
fs.appendFileSync(process.env.CALLS, JSON.stringify(args) + '\\n')
fs.appendFileSync(process.env.ORDER, 'xcodebuild ' + args.join(' ') + '\\n')
console.log('COMPLETE_STOREKIT_LOG_' + args[0])
process.exit(args[0] === 'build-for-testing' ? Number(process.env.BUILD_EXIT) : Number(process.env.UI_EXIT) || Number(process.env.SDK_EXIT))
`, { mode: 0o755 })
      const callsPath = join(task, 'calls')
      const orderPath = join(task, 'order')
      const run = spawnSync('bash', ['scripts/run-ios-storekit-tests.sh'], {
        cwd: root,
        encoding: 'utf8',
        timeout: 10_000,
        env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, DEVICE: 'existing-phone', RUNNER_TEMP: task, CALLS: callsPath, ORDER: orderPath, BUILD_EXIT: String(buildExit), UI_EXIT: String(uiExit), SDK_EXIT: String(sdkExit) },
      })
      assert.equal(run.error, undefined)
      assert.equal(run.status, buildExit || uiExit || sdkExit)
      const calls = readFileSync(callsPath, 'utf8').trim().split('\n').map(JSON.parse)
      const commands = readFileSync(orderPath, 'utf8').trim().split('\n')
      assert.ok(commands.indexOf('xcrun simctl boot existing-phone') < commands.findIndex(command => command.startsWith('xcodebuild build-for-testing')))
      assert.doesNotMatch(commands.join('\n'), /launchctl kickstart/)
      assert.equal(calls.length, expectedCalls)
      for (const args of calls) {
        assert.ok(args.includes('-onlyUsePackageVersionsFromResolvedFile'))
        assert.ok(args.includes('-skipPackageUpdates'))
        assert.ok(args.includes(`ARCHS=${hostArchitecture}`))
        assert.ok(args.includes('ONLY_ACTIVE_ARCH=YES'))
        assert.equal(args[args.indexOf('-scheme') + 1], 'CookMembershipStoreKit')
        assert.equal(args[args.indexOf('-testPlan') + 1], 'CookMembershipStoreKit')
        assert.ok(args.includes('CODE_SIGNING_ALLOWED=YES'))
        assert.ok(args.includes('CODE_SIGN_IDENTITY=-'))
        assert.equal(args[args.indexOf('-parallel-testing-enabled') + 1], 'NO')
        assert.match(run.stdout, new RegExp(`COMPLETE_STOREKIT_LOG_${args[0]}`))
      }
      if (calls.length === 2) {
        for (const args of calls.slice(1)) {
          assert.equal(args[args.indexOf('-destination') + 1], 'id=existing-phone')
          assert.ok(args.includes('-resultBundlePath'))
          assert.equal(args[args.indexOf('-maximum-test-execution-time-allowance') + 1], '120')
        }
        assert.ok(calls[1].includes('-only-testing:CookStoreKitTests'))
        assert.ok(calls[1].indexOf('-only-testing:CookStoreKitTests') < calls[1].indexOf('-only-testing:CookUITests/MembershipStoreKitUITests'))
      }
    }
    finally { rmSync(task, { recursive: true, force: true }) }
  }
})

test('a hung StoreKit daemon restart is stopped before the readiness probe', { timeout: 15_000 }, () => {
  const task = mkdtempSync(join(tmpdir(), 'cook-storekit-stuck-service-'))
  try {
    const bin = join(task, 'bin')
    mkdirSync(bin)
    writeFileSync(join(bin, 'xcrun'), `#!/bin/sh
printf 'xcrun %s\\n' "$*" >> "$ORDER"
case "$*" in
  "simctl spawn existing-phone launchctl kickstart -k user/501/com.apple.storekitd")
    : > "$RESTARTED"
    trap '' TERM
    echo STOREKIT_RESTART_HUNG
    while :; do sleep 1; done
    ;;
  "simctl spawn existing-phone launchctl print user/501/com.apple.storekitd")
    if [ -f "$RESTARTED" ]; then echo "state = running"; else echo "state = not running"; fi
    ;;
esac
exit 0
`, { mode: 0o755 })
    writeFileSync(join(bin, 'xcodebuild'), `#!/usr/bin/env node
const fs = require('node:fs')
const args = process.argv.slice(2)
fs.appendFileSync(process.env.CALLS, JSON.stringify(args) + '\\n')
fs.appendFileSync(process.env.ORDER, 'xcodebuild ' + args.join(' ') + '\\n')
`, { mode: 0o755 })
    const callsPath = join(task, 'calls')
    const orderPath = join(task, 'order')
    const run = spawnSync('bash', ['scripts/run-ios-storekit-tests.sh'], {
      cwd: root,
      encoding: 'utf8',
      timeout: 10_000,
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        DEVICE: 'existing-phone',
        RUNNER_TEMP: task,
        CALLS: callsPath,
        ORDER: orderPath,
        RESTARTED: join(task, 'restarted'),
        STOREKIT_RESTART_TIMEOUT_SECONDS: '1',
      },
    })
    assert.equal(run.error, undefined)
    assert.equal(run.status, 0)
    assert.match(run.stderr, /StoreKit service restart did not finish within 1 seconds/)
    assert.equal(readFileSync(callsPath, 'utf8').trim().split('\n').length, 2)
    const commands = readFileSync(orderPath, 'utf8').trim().split('\n')
    assert.ok(commands.indexOf('xcrun simctl boot existing-phone') < commands.findIndex(command => command.startsWith('xcodebuild build-for-testing')))
    assert.match(commands.join('\n'), /launchctl kickstart -k user\/501\/com\.apple\.storekitd/)
  }
  finally { rmSync(task, { recursive: true, force: true }) }
})

test('simulator cleanup is bounded after successful StoreKit checks', { timeout: 15_000 }, () => {
  const task = mkdtempSync(join(tmpdir(), 'cook-storekit-stuck-shutdown-'))
  try {
    const bin = join(task, 'bin')
    mkdirSync(bin)
    writeFileSync(join(bin, 'xcrun'), `#!/bin/sh
printf '%s\\n' "$*" >> "$CALLS"
case "$*" in
  "simctl shutdown existing-phone")
    trap '' TERM
    echo STOREKIT_SHUTDOWN_HUNG
    while :; do sleep 1; done
    ;;
  "simctl spawn existing-phone launchctl print user/501/com.apple.storekitd")
    echo "state = running"
    ;;
esac
exit 0
`, { mode: 0o755 })
    writeFileSync(join(bin, 'xcodebuild'), `#!/usr/bin/env node
const fs = require('node:fs')
fs.appendFileSync(process.env.CALLS, 'xcodebuild ' + process.argv.slice(2).join(' ') + '\\n')
`, { mode: 0o755 })
    const callsPath = join(task, 'calls')
    const run = spawnSync('bash', ['scripts/run-ios-storekit-tests.sh'], {
      cwd: root,
      encoding: 'utf8',
      timeout: 10_000,
      env: {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        DEVICE: 'existing-phone',
        RUNNER_TEMP: task,
        CALLS: callsPath,
        STOREKIT_SHUTDOWN_TIMEOUT_SECONDS: '1',
      },
    })
    assert.equal(run.error, undefined)
    assert.equal(run.status, 0)
    assert.match(readFileSync(callsPath, 'utf8'), /simctl shutdown existing-phone/)
    assert.match(readFileSync(join(task, 'xcresult/existing-phone-storekit-shutdown.log'), 'utf8'), /STOREKIT_SHUTDOWN_HUNG/)
  }
  finally { rmSync(task, { recursive: true, force: true }) }
})

test('core StoreKit contracts run once in parallel while financial changes keep both devices', () => {
  const workflow = readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8')
  const nativeJob = workflow.slice(workflow.indexOf('\n  ios-tests:\n'), workflow.indexOf('\n  ios-storekit:\n'))
  const storekitJob = workflow.slice(workflow.indexOf('\n  ios-storekit:\n'), workflow.indexOf('\n  ios:\n'))
  const gate = workflow.slice(workflow.indexOf('\n  ios:\n'))
  assert.doesNotMatch(nativeJob, /run-ios-storekit-tests\.sh/)
  assert.match(storekitJob, /ios_storekit_devices/)
  assert.match(storekitJob, /name: Verify local StoreKit contracts\n\s+id: storekit_tests\n\s+timeout-minutes: 10/)
  assert.match(storekitJob, /run: bash scripts\/run-ios-storekit-tests\.sh/)
  assert.doesNotMatch(storekitJob, /continue-on-error/)
  assert.match(gate, /needs: \[changes, ios-core-reuse, ios-tests, ios-storekit\]/)
  assert.match(gate, /STOREKIT_RESULT: \$\{\{ needs\.ios-storekit\.result \}\}/)
  assert.match(gate, /\[\[ "\$IOS_RESULT" == success && "\$STOREKIT_RESULT" == success \]\]/)
  assert.deepEqual(planIosTests('pull_request').storekitDevices, ['iphone'])
  assert.deepEqual(planIosTests('push', undefined, 'membership').storekitDevices, ['iphone', 'ipad'])
  assert.deepEqual(planIosTests('workflow_dispatch', 'core').storekitDevices, ['iphone'])
  assert.deepEqual(planIosTests('workflow_dispatch', 'full').storekitDevices, ['iphone', 'ipad'])
  assert.deepEqual(planIosTests('workflow_dispatch', 'membership').storekitDevices, ['iphone', 'ipad'])
})
