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
    writeFileSync(join(bin, 'xcrun'), '#!/bin/sh\nexit 0\n', { mode: 0o755 })
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
  assert.match(project.slice(project.indexOf('\n  CookMembershipStoreKit:')), /targets: \[CookUITests, CookStoreKitTests\]/)
  for (const [buildExit, uiExit, sdkExit, expectedCalls] of [[0, 0, 0, 2], [65, 0, 0, 1], [0, 65, 0, 2], [0, 0, 66, 2], [0, 65, 66, 2]]) {
    const task = mkdtempSync(join(tmpdir(), 'cook-storekit-ci-'))
    try {
      const bin = join(task, 'bin')
      mkdirSync(bin)
      writeFileSync(join(bin, 'xcrun'), '#!/bin/sh\nexit 0\n', { mode: 0o755 })
      writeFileSync(join(bin, 'xcodebuild'), `#!/usr/bin/env node
const fs = require('node:fs')
const args = process.argv.slice(2)
fs.appendFileSync(process.env.CALLS, JSON.stringify(args) + '\\n')
console.log('COMPLETE_STOREKIT_LOG_' + args[0])
process.exit(args[0] === 'build-for-testing' ? Number(process.env.BUILD_EXIT) : Number(process.env.UI_EXIT) || Number(process.env.SDK_EXIT))
`, { mode: 0o755 })
      const callsPath = join(task, 'calls')
      const run = spawnSync('bash', ['scripts/run-ios-storekit-tests.sh'], {
        cwd: root,
        encoding: 'utf8',
        timeout: 10_000,
        env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, DEVICE: 'existing-phone', RUNNER_TEMP: task, CALLS: callsPath, BUILD_EXIT: String(buildExit), UI_EXIT: String(uiExit), SDK_EXIT: String(sdkExit) },
      })
      assert.equal(run.error, undefined)
      assert.equal(run.status, buildExit || uiExit || sdkExit)
      const calls = readFileSync(callsPath, 'utf8').trim().split('\n').map(JSON.parse)
      assert.equal(calls.length, expectedCalls)
      assert.ok(calls[0].includes('-onlyUsePackageVersionsFromResolvedFile'))
      assert.ok(calls[0].includes('-skipPackageUpdates'))
      for (const args of calls) {
        assert.equal(args[args.indexOf('-scheme') + 1], 'CookMembershipStoreKit')
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
        assert.ok(calls[1].includes('-only-testing:CookUITests/MembershipStoreKitUITests'))
        assert.ok(calls[1].includes('-only-testing:CookStoreKitTests'))
      }
    }
    finally { rmSync(task, { recursive: true, force: true }) }
  }
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
