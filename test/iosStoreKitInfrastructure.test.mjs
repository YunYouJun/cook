import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
// eslint-disable-next-line test/no-import-node-test -- Verify the actual financial CI shell before installing workspace packages.
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))

test('StoreKit uses its dedicated signed scheme and preserves build/test failures', () => {
  for (const [buildExit, testExit, expectedCalls] of [[0, 0, 2], [65, 0, 1], [0, 65, 2]]) {
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
process.exit(Number(args[0] === 'build-for-testing' ? process.env.BUILD_EXIT : process.env.TEST_EXIT))
`, { mode: 0o755 })
      const callsPath = join(task, 'calls')
      const run = spawnSync('bash', ['scripts/run-ios-storekit-tests.sh'], {
        cwd: root,
        encoding: 'utf8',
        timeout: 10_000,
        env: { ...process.env, PATH: `${bin}:${process.env.PATH}`, DEVICE: 'existing-phone', RUNNER_TEMP: task, CALLS: callsPath, BUILD_EXIT: String(buildExit), TEST_EXIT: String(testExit) },
      })
      assert.equal(run.error, undefined)
      assert.equal(run.status, buildExit || testExit)
      const calls = readFileSync(callsPath, 'utf8').trim().split('\n').map(JSON.parse)
      assert.equal(calls.length, expectedCalls)
      for (const args of calls) {
        assert.equal(args[args.indexOf('-scheme') + 1], 'CookMembershipStoreKit')
        assert.ok(args.includes('CODE_SIGNING_ALLOWED=YES'))
        assert.ok(args.includes('CODE_SIGN_IDENTITY=-'))
        assert.equal(args[args.indexOf('-parallel-testing-enabled') + 1], 'NO')
        assert.match(run.stdout, new RegExp(`COMPLETE_STOREKIT_LOG_${args[0]}`))
      }
      if (calls.length === 2) {
        assert.equal(calls[1][calls[1].indexOf('-destination') + 1], 'id=existing-phone')
        assert.ok(calls[1].includes('-resultBundlePath'))
      }
    }
    finally { rmSync(task, { recursive: true, force: true }) }
  }
})

test('every core and membership matrix entry requires the StoreKit step before recording reuse', () => {
  const workflow = readFileSync(join(root, '.github/workflows/ci.yml'), 'utf8')
  const job = workflow.slice(workflow.indexOf('\n  ios-tests:\n'), workflow.indexOf('\n  ios:\n'))
  assert.match(job, /name: Build for testing\n\s+timeout-minutes: 15\n\s+id: native_build/)
  assert.match(job, /name: Verify local StoreKit contracts\n\s+id: storekit_tests\n\s+if: \$\{\{ !cancelled\(\) && steps\.native_build\.outcome == 'success' && contains\(/)
  assert.match(job, /contains\(fromJSON\('\["core-regression", "membership", "regression-main"\]'\), matrix.suite\)/)
  assert.match(job, /if: .*steps\.ios_tests\.outcome == 'success' && steps\.storekit_tests\.outcome == 'success'/)
  assert.match(job, /run: bash scripts\/run-ios-storekit-tests.sh/)
  assert.ok(job.indexOf('name: Verify local StoreKit contracts') < job.indexOf('name: Identify the tested PR core environment'))
  assert.doesNotMatch(job.slice(job.indexOf('name: Verify local StoreKit contracts'), job.indexOf('name: Identify the tested PR core environment')), /continue-on-error/)
})
