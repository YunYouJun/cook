import { execFileSync } from 'node:child_process'
import { appendFileSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const readmes = new Set([
  'README.md',
  'README.en.md',
  'CONTRIBUTING.md',
  'CHANGELOG.md',
  'CLAUDE.md',
  'AGENTS.md',
  'apps/ios/README.md',
  'apps/ios/CookCore/README.md',
  'apps/web/README.md',
])

function isDocumentation(path) {
  // This policy is bundled into the native App by apps/ios/project.yml.
  if (path.toLowerCase() === 'docs/privacy.md')
    return false

  return readmes.has(path)
    || (path.startsWith('docs/') && /\.(?:md|png|jpe?g|gif|webp|svg|ico)$/.test(path))
    || (path.startsWith('specs/') && path.endsWith('.md'))
}

// Only browser-owned inputs are exempt. Protocol sources, native resources,
// generators, shared data, dependencies and unclassified paths fail closed.
// userData.ts lives under apps/web but also generates the server sync contract.
const webTestFiles = new Set([
  'acquisition',
  'analytics',
  'appFeedbackFlow',
  'authInitialization',
  'authInitializationPlugin',
  'clarityPlugin',
  'component',
  'cookFunnel',
  'feedbackContext',
  'feedbackMail',
  'feedbackRemote',
  'landingData',
  'phoneDemo',
  'privateRecipeStorage',
  'seo',
  'userDataPages',
  'userDataStore',
  'userDataSync',
  'userDataSyncComposable',
  'userDataSyncSdk',
  'yunlefunAuth',
  'yunlefunAuthAdapter',
  'yunlefunAuthComposable',
  'yunlefunSsoConfig',
  'yunleHost',
].map(name => `test/${name}.test.ts`))

function isWebOnly(path) {
  return /^apps\/web\/app\/(?:adapters|assets|components|composables|constants|layouts|middleware|pages|plugins|stores)\//.test(path)
    || /^apps\/web\/app\/utils\/(?:acquisition|analytics|authInitialization|userDataSync|userDataSyncSdk)\.ts$/.test(path)
    || webTestFiles.has(path)
}

function isMembershipInput(path) {
  return /^apps\/ios\/(?:Cook\/(?:Model\/(?:NativeMembershipStoreKit|Membership\w*)|Views\/Membership\w*)|CookCore\/(?:Sources\/CookCore|Tests\/CookCoreTests)\/Membership\w*|Cook(?:UI)?Tests\/Membership\w*)\.swift$/.test(path)
    || path.startsWith('apps/ios/StoreKitValidation/')
}

export function planIosTests(eventName, coverage, scope) {
  if (eventName === 'workflow_dispatch' && coverage === 'membership') {
    return {
      suites: ['membership'],
      maxParallel: 2,
      storekitDevices: ['iphone', 'ipad'],
    }
  }
  const core = eventName === 'push' || eventName === 'pull_request'
    || (eventName === 'workflow_dispatch' && coverage === 'core')
  if (core && eventName !== 'workflow_dispatch' && scope === 'membership') {
    return {
      suites: ['membership'],
      maxParallel: 2,
      storekitDevices: ['iphone', 'ipad'],
    }
  }
  return {
    suites: core ? ['core-regression'] : ['regression-main', 'regression-discovery', 'dietary'],
    maxParallel: core ? 2 : 3,
    // Core PRs exercise device-independent financial contracts once. Membership
    // changes and full audits retain both form factors.
    storekitDevices: core ? ['iphone'] : ['iphone', 'ipad'],
  }
}

export function detectNativeChanges(eventName, event, cwd = process.cwd()) {
  try {
    let base
    let head
    let separator
    if (eventName === 'pull_request') {
      base = event.pull_request?.base?.sha
      head = event.pull_request?.head?.sha
      separator = '...'
    }
    else if (eventName === 'push') {
      base = event.before
      head = event.after
      separator = '..'
    }
    else {
      throw new Error('Unsupported event')
    }

    if (![base, head].every(sha => typeof sha === 'string' && /^[a-f\d]{40}$/i.test(sha) && !/^0+$/.test(sha)))
      throw new Error('Missing comparison commits')

    // Disabling rename detection includes both the removed and added paths.
    // NUL delimiters preserve paths containing spaces or newlines.
    const diff = execFileSync('git', ['diff', '--name-only', '--no-renames', '-z', `${base}${separator}${head}`, '--'], {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    const paths = diff.split('\0').filter(Boolean)
    return {
      skipNative: paths.length > 0 && paths.every(path => isDocumentation(path) || isWebOnly(path) || path === '.github/workflows/support-sync.yml'),
      scope: paths.some(isMembershipInput) && paths.every(path => isMembershipInput(path) || isDocumentation(path) || isWebOnly(path)) ? 'membership' : 'core',
      changedFiles: paths.length,
      comparison: `${base}${separator}${head}`,
    }
  }
  catch {
    // An unavailable base (including a force push/new branch) must never skip tests.
    return { skipNative: false, changedFiles: null, comparison: 'unavailable' }
  }
}

function previousPushPassed(event) {
  const repo = process.env.GITHUB_REPOSITORY
  if (!repo || typeof event.ref !== 'string' || !event.ref.startsWith('refs/heads/'))
    return false

  const branch = event.ref.slice('refs/heads/'.length)
  try {
    // A Web/docs/Support push can cancel the only run that covers the previous
    // native change. Carry its coverage forward only from a successful push.
    const output = execFileSync('gh', [
      'api',
      `repos/${repo}/actions/workflows/ci.yml/runs`,
      '-X',
      'GET',
      '-f',
      'event=push',
      '-f',
      `branch=${branch}`,
      '-f',
      `head_sha=${event.before}`,
      '-f',
      'status=success',
      '-f',
      'per_page=1',
    ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 10000 })
    return JSON.parse(output).workflow_runs.some(run =>
      run.head_sha === event.before && run.head_branch === branch
      && run.event === 'push' && run.conclusion === 'success')
  }
  catch {
    return false
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let event
  try {
    event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'))
  }
  catch {
    event = {}
  }
  const result = detectNativeChanges(process.env.GITHUB_EVENT_NAME, event)
  if (result.skipNative && process.env.GITHUB_EVENT_NAME === 'push') {
    result.previousPushPassed = previousPushPassed(event)
    result.skipNative = result.previousPushPassed
  }
  const ios = planIosTests(process.env.GITHUB_EVENT_NAME, event?.inputs?.ios_coverage, result.scope)
  if (process.env.GITHUB_OUTPUT) {
    appendFileSync(process.env.GITHUB_OUTPUT, [
      `skip_native=${result.skipNative}`,
      `ios_suites=${JSON.stringify(ios.suites)}`,
      `ios_max_parallel=${ios.maxParallel}`,
      `ios_storekit_devices=${JSON.stringify(ios.storekitDevices)}`,
      '',
    ].join('\n'))
  }
  if (process.env.GITHUB_STEP_SUMMARY) {
    const reason = result.previousPushPassed === false ? ' Previous push did not have a verified successful CI run.' : ''
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `Native CI: ${result.skipNative ? 'not required (only browser-owned inputs, documentation or the Support sync workflow changed)' : ios.suites.join(', ')}. StoreKit devices: ${ios.storekitDevices.join(', ')}.${reason} Changed paths: ${result.changedFiles ?? 'unknown'}. Comparison: ${result.comparison}.\n`)
  }
  console.log(JSON.stringify({ ...result, ios }))
}
