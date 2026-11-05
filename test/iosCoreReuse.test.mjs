import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
// eslint-disable-next-line test/no-import-node-test -- The CI planner runs before workspace dependencies are installed.
import { test } from 'node:test'
import { findCoreRun } from '../scripts/find-ios-core-run.mjs'
import { currentKey } from '../scripts/ios-core-evidence.mjs'

const repo = 'example/cook'
const base = '1'.repeat(40)
const head = '2'.repeat(40)
const checkout = '3'.repeat(40)
const merge = '4'.repeat(40)
const tree = '5'.repeat(40)
const key = 'a'.repeat(64)

function evidence() {
  const pr = { number: 193, merge_commit_sha: merge, merged_at: '2026-11-05T00:00:00Z', base: { ref: 'main', repo: { full_name: repo } }, head: { sha: head, ref: 'topic', repo: { full_name: repo } } }
  const run = { id: 725, run_attempt: 2, event: 'pull_request', path: '.github/workflows/ci.yml', head_sha: head, head_branch: 'topic', head_repository: { full_name: repo }, repository: { full_name: repo }, status: 'completed', conclusion: 'success' }
  const jobs = ['iphone', 'ipad'].map(device => ({
    name: `ios-tests (${device}, core-regression)`,
    run_id: 725,
    run_attempt: 2,
    status: 'completed',
    conclusion: 'success',
    labels: ['xcode-27'],
    steps: ['Build for testing', 'Build and test', `Verified iOS core #193 ${checkout} ${key}`].map(name => ({ name, status: 'completed', conclusion: 'success' })),
  }))
  const commit = { sha: checkout, tree: { sha: tree }, parents: [{ sha: base }, { sha: head }] }
  const input = {
    eventName: 'push',
    event: { ref: 'refs/heads/main', before: base, after: merge },
    repo,
    checkout: merge,
    parent: base,
    tree,
    api(path, query) {
      if (path === `commits/${merge}/pulls`)
        return [pr]
      if (path === 'actions/workflows/ci.yml/runs') {
        assert.deepEqual(query, { event: 'pull_request', head_sha: head, per_page: '1' })
        return { workflow_runs: [run] }
      }
      if (path === 'actions/runs/725/attempts/2/jobs')
        return { total_count: jobs.length, jobs }
      if (path === `git/commits/${checkout}`)
        return commit
      throw new Error(`Unexpected API request ${path}`)
    },
  }
  return { input, pr, run, jobs, commit }
}

test('a merged PR must match the checkout actually tested by both core jobs', () => {
  assert.deepEqual(findCoreRun(evidence().input), { key, runId: 725, attempt: 2, pr: 193 })
})

test('unrelated merges, old workflows, missing or partial test evidence cannot skip either device', () => {
  for (const mutate of [
    ({ pr }) => { pr.merge_commit_sha = head },
    ({ pr }) => { pr.merged_at = null },
    ({ pr }) => { pr.base.ref = 'dev' },
    ({ pr }) => { pr.head.repo.full_name = 'fork/cook' },
    ({ run }) => { run.path = '.github/workflows/other.yml' },
    ({ run }) => { run.event = 'workflow_dispatch' },
    ({ run }) => { run.conclusion = 'failure' },
    ({ run }) => { run.status = 'in_progress' },
    ({ run }) => { run.head_sha = merge },
    ({ jobs }) => { jobs.pop() },
    ({ jobs }) => { jobs.push(jobs[0]) },
    ({ jobs }) => { jobs[1].run_attempt = 1 },
    ({ jobs }) => { jobs[1].conclusion = 'cancelled' },
    ({ jobs }) => { jobs[0].name = 'ios-tests (iphone, dietary)' },
    ({ jobs }) => { jobs[1].steps[1].conclusion = 'skipped' },
    ({ jobs }) => { jobs[0].steps.pop() },
    ({ jobs }) => { jobs[1].steps[2].name = jobs[1].steps[2].name.replace(key, 'b'.repeat(64)) },
    ({ jobs }) => { jobs[0].steps[2].name = jobs[0].steps[2].name.replace('#193', '#194') },
    ({ commit }) => { commit.parents[0].sha = head },
    ({ commit }) => { commit.parents[1].sha = base },
    ({ commit }) => { commit.tree.sha = '6'.repeat(40) },
  ]) {
    const fixture = evidence()
    mutate(fixture)
    assert.equal(findCoreRun(fixture.input), undefined, String(mutate))
  }
})

test('manual full, manual core, PR, forced and multi-commit pushes never consult reuse evidence', () => {
  for (const change of [
    { eventName: 'workflow_dispatch', event: { inputs: { ios_coverage: 'full' } } },
    { eventName: 'workflow_dispatch', event: { inputs: { ios_coverage: 'core' } } },
    { eventName: 'pull_request' },
    { parent: checkout },
    { event: { ref: 'refs/heads/main', after: merge, before: base, forced: true } },
  ]) {
    assert.equal(findCoreRun({ ...evidence().input, ...change, api: assert.fail }), undefined)
  }
})

function toolchain() {
  const runtime = 'com.apple.CoreSimulator.SimRuntime.iOS-27-0'
  const inventory = {
    devices: { [runtime]: ['iPhone', 'iPad'].map(family => ({ udid: family, isAvailable: true, deviceTypeIdentifier: `com.apple.CoreSimulator.SimDeviceType.${family}-pro` })) },
    runtimes: [{ isAvailable: true, identifier: runtime, version: '27.0', buildversion: '24A434' }],
  }
  const env = {
    GITHUB_SHA: checkout,
    GITHUB_WORKFLOW_SHA: checkout,
    GITHUB_REPOSITORY: repo,
    GITHUB_WORKFLOW_REF: `${repo}/.github/workflows/ci.yml@refs/pull/193/merge`,
    ImageOS: 'xcode-27-arm64',
    ImageVersion: '20261104',
    DEVELOPER_DIR: '/Applications/Xcode_27.1.app/Contents/Developer',
    IPHONE: 'iPhone',
    IPAD: 'iPad',
  }
  const versions = {
    'git rev-parse HEAD': checkout,
    'git rev-parse HEAD^{tree}': tree,
    [`git rev-parse ${checkout}:.github/workflows/ci.yml`]: '7'.repeat(40),
    'sw_vers -buildVersion': '26A1',
    'xcodebuild -version': 'Xcode 27.1 Build version 18B56',
    'xcrun swiftc --version': 'Apple Swift 6.3',
    'xcrun --sdk iphonesimulator --show-sdk-build-version': '24A435',
    'xcodegen --version': '2.45.0',
    'which xcodegen': '/tools/xcodegen',
    'pnpm --version': '11.21.0',
  }
  const pins = { revision: '5ce0ad7' }
  const binaries = { xcodegen: 'installed binary contents' }
  const fingerprint = () => currentKey(env, (...args) => {
    const cmd = args.join(' ')
    if (cmd === 'xcrun simctl list -j')
      return JSON.stringify(inventory)
    assert(Object.hasOwn(versions, cmd), cmd)
    return versions[cmd]
  }, path => Buffer.from(path === '/tools/xcodegen' ? binaries.xcodegen : JSON.stringify(pins)))
  return { env, versions, inventory, pins, binaries, fingerprint }
}

test('source tree, executing workflow, tools, runner and both simulated devices are part of the fingerprint', () => {
  const original = toolchain().fingerprint()
  assert.match(original, /^[a-f\d]{64}$/)
  for (const mutate of [
    ({ versions }) => { versions['git rev-parse HEAD^{tree}'] = head },
    ({ versions }) => { versions[`git rev-parse ${checkout}:.github/workflows/ci.yml`] = head },
    ({ versions }) => { versions['xcodebuild -version'] += '.1' },
    ({ versions }) => { versions['xcrun swiftc --version'] += '.1' },
    ({ versions }) => { versions['xcrun --sdk iphonesimulator --show-sdk-build-version'] += '.1' },
    ({ versions }) => { versions['xcodegen --version'] += '.1' },
    ({ versions }) => { versions['pnpm --version'] += '.1' },
    ({ versions }) => { versions['sw_vers -buildVersion'] += '.1' },
    ({ env }) => { env.ImageVersion += '.1' },
    ({ pins }) => { pins.revision += '1' },
    ({ binaries }) => { binaries.xcodegen += '1' },
    ({ inventory }) => { inventory.runtimes[0].buildversion += 'a' },
    ({ inventory }) => { Object.values(inventory.devices)[0][1].deviceTypeIdentifier += '-M5' },
  ]) {
    const fixture = toolchain()
    mutate(fixture)
    assert.notEqual(fixture.fingerprint(), original, String(mutate))
  }
  const replacement = toolchain()
  Object.values(replacement.inventory.devices)[0][1].udid = replacement.env.IPAD = 'new-machine'
  assert.equal(replacement.fingerprint(), original)
  replacement.inventory.runtimes[0].isAvailable = false
  assert.throws(replacement.fingerprint)
  const missing = toolchain()
  delete missing.env.ImageVersion
  assert.throws(missing.fingerprint)
})

test('the macOS probe grants reuse only for a nonempty exact fingerprint match', () => {
  const workflow = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8')
  const step = workflow.split('      - name: Compare with both successful PR jobs')[1].split(/\n {2}\S/)[0]
  const script = step.split('        run: |\n')[1].split('\n').map(line => line.slice(10)).join('\n')
  const directory = mkdtempSync(join(tmpdir(), 'cook-core-probe-'))
  try {
    for (const [expected, actual, hit] of [[key, key, true], [key, 'b'.repeat(64), false], [key, '', false], ['', '', false]]) {
      const output = join(directory, 'output')
      writeFileSync(output, '')
      execFileSync('bash', ['-e', '-c', script], {
        env: { ...process.env, EXPECTED: expected, ACTUAL: actual, GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: join(directory, 'summary') },
      })
      assert.equal(readFileSync(output, 'utf8'), hit ? 'verified=true\n' : '')
    }
  }
  finally {
    rmSync(directory, { recursive: true, force: true })
  }
})
