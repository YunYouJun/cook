import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { appendFileSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

function required(value, label) {
  if (typeof value !== 'string' || !value.trim())
    throw new Error(`Missing ${label}`)
  return value.trim()
}

function command(tool, ...args) {
  try {
    return required(execFileSync(tool, args, { encoding: 'utf8', timeout: 20000, stdio: ['ignore', 'pipe', 'pipe'] }), tool)
  }
  catch {
    throw new Error(`Could not read ${tool} ${args.join(' ')}`)
  }
}

export function simulatorIdentity(inventory, udid, family) {
  for (const [runtimeId, devices] of Object.entries(inventory.devices)) {
    const device = devices.find(d => d.udid === udid && d.isAvailable)
    if (!device)
      continue
    const runtime = inventory.runtimes.find(r => r.identifier === runtimeId && r.isAvailable)
    if (!device.deviceTypeIdentifier?.startsWith(`com.apple.CoreSimulator.SimDeviceType.${family}-`) || !runtime)
      break
    // UDIDs identify disposable machines, not the model or OS that was tested.
    return [device.deviceTypeIdentifier, runtimeId, required(runtime.version, 'runtime version'), required(runtime.buildversion, 'runtime build')]
  }
  throw new Error(`No compatible ${family} runtime for the selected device`)
}

function evidenceKey(identity) {
  return createHash('sha256').update(JSON.stringify(identity)).digest('hex')
}

export function currentKey(env = process.env, run = command, read = readFileSync) {
  if (run('git', 'rev-parse', 'HEAD') !== required(env.GITHUB_SHA, 'event SHA'))
    throw new Error('Checkout does not match the event SHA')
  if (env.GITHUB_WORKFLOW_REF?.split('@')[0] !== `${env.GITHUB_REPOSITORY}/.github/workflows/ci.yml`)
    throw new Error('Unexpected workflow')
  const inventory = JSON.parse(run('xcrun', 'simctl', 'list', '-j'))
  const workflow = run('git', 'rev-parse', `${required(env.GITHUB_WORKFLOW_SHA, 'workflow SHA')}:.github/workflows/ci.yml`)
  // Include the actual resolved Swift dependencies in addition to the source
  // tree (which includes all scripts, local actions, lockfiles and build inputs).
  const resolved = read('apps/ios/Cook.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved')
  return evidenceKey({
    schema: 1,
    tree: run('git', 'rev-parse', 'HEAD^{tree}'),
    workflow,
    runner: [required(env.ImageOS, 'runner image'), required(env.ImageVersion, 'runner image version'), process.arch, run('sw_vers', '-buildVersion')],
    developer: required(env.DEVELOPER_DIR, 'developer directory'),
    xcode: run('xcodebuild', '-version'),
    swift: run('xcrun', 'swiftc', '--version'),
    sdk: run('xcrun', '--sdk', 'iphonesimulator', '--show-sdk-build-version'),
    xcodegen: run('xcodegen', '--version'),
    node: process.version,
    pnpm: run('pnpm', '--version'),
    packages: createHash('sha256').update(resolved).digest('hex'),
    iphone: simulatorIdentity(inventory, env.IPHONE, 'iPhone'),
    ipad: simulatorIdentity(inventory, env.IPAD, 'iPad'),
  })
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const key = currentKey()
    appendFileSync(process.env.GITHUB_OUTPUT, `key=${key}\n`)
  }
  catch (error) {
    // Evidence is optional. A PR still reports its real test outcome, and a
    // push with an incomplete fingerprint always builds and tests both devices.
    console.log('This core result cannot be reused:', error.message)
  }
}
