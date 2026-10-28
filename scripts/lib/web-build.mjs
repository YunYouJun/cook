import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import process from 'node:process'

/** Root-relative static endpoint used by production deployment verification. */
export const BUILD_INFO_PATH = '.well-known/cook-build.json'
const SHA_PATTERN = /^[0-9a-f]{40}$/i
const VERSION_PATTERN = /^\d+\.\d+\.\d+(?:-[\dA-Z.-]+)?(?:\+[\dA-Z.-]+)?$/i
const COMMIT_ENV_KEYS = ['GITHUB_SHA', 'CF_PAGES_COMMIT_SHA']

function readGit(root, args) {
  try {
    return execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  }
  catch {
    throw new Error('Build provenance requires a Git checkout with a readable HEAD')
  }
}

function validateSha(value) {
  if (typeof value !== 'string' || !SHA_PATTERN.test(value))
    throw new Error('Build provenance requires a complete 40-character Git SHA')
  return value.toLowerCase()
}

function validateVersion(value) {
  if (typeof value !== 'string' || !VERSION_PATTERN.test(value))
    throw new Error('Build provenance requires a valid application version')
  return value
}

/**
 * Capture the checked-out source before generation, without writing a checkpoint.
 *
 * @param {string} repositoryRoot Root of the actual Git checkout.
 * @param {NodeJS.ProcessEnv} [environment] Build environment; only known commit keys are inspected.
 * @returns {{ commitSha: string, version: string, dirty: boolean }} Immutable source snapshot.
 * @throws {Error} If Git is unavailable or a provider's expected commit disagrees with HEAD.
 */
export function captureBuildInput(repositoryRoot, environment = process.env) {
  const root = realpathSync(resolve(repositoryRoot))
  const gitRoot = realpathSync(readGit(root, ['rev-parse', '--show-toplevel']))
  if (gitRoot !== root)
    throw new Error('Build provenance must be captured at the repository root')
  const commitSha = validateSha(readGit(root, ['rev-parse', '--verify', 'HEAD']))
  for (const key of COMMIT_ENV_KEYS) {
    if (environment[key] && validateSha(environment[key]) !== commitSha)
      throw new Error(`Build provenance disagrees with ${key}`)
  }
  const version = validateVersion(JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version)
  const dirty = Boolean(readGit(root, ['status', '--porcelain=v1', '--untracked-files=normal', '--ignore-submodules=none']))
  return Object.freeze({ commitSha, version, dirty })
}

/**
 * Describe a finished build only when its checked-out commit and version stayed fixed.
 *
 * @param {string} repositoryRoot Root of the actual Git checkout.
 * @param {{ commitSha: string, version: string, dirty: boolean }} input Snapshot captured before generation.
 * @param {{ environment?: NodeJS.ProcessEnv, now?: () => Date }} [options] Known build environment and clock.
 * @returns {{ schemaVersion: number, productId: string, repository: string, commitSha: string, version: string, builtAt: string, dirty: boolean }} Public, allowlisted build metadata.
 * @throws {Error} If the source commit or application version changed during generation.
 */
export function createBuildInfo(repositoryRoot, input, { environment = process.env, now = () => new Date() } = {}) {
  if (!input || typeof input.dirty !== 'boolean')
    throw new Error('Build provenance requires the pre-generation source snapshot')
  const beforeSha = validateSha(input.commitSha)
  const beforeVersion = validateVersion(input.version)
  const after = captureBuildInput(repositoryRoot, environment)
  if (after.commitSha !== beforeSha || after.version !== beforeVersion)
    throw new Error('Build source changed during generation; rebuild from a fixed checkout')
  return {
    schemaVersion: 1,
    productId: 'cook',
    repository: 'YunYouJun/cook',
    commitSha: after.commitSha,
    version: after.version,
    builtAt: now().toISOString(),
    dirty: input.dirty || after.dirty,
  }
}

/**
 * Mirror generated web assets into the deployment output with their verified source snapshot.
 *
 * @param {string} repositoryRoot Repository containing apps/web/dist.
 * @param {{ commitSha: string, version: string, dirty: boolean }} input Snapshot captured before generation.
 * @param {{ environment?: NodeJS.ProcessEnv, now?: () => Date }} [options] Environment and clock for provenance validation.
 * @returns {string} Absolute root dist directory.
 * @throws {Error} If generation did not produce its entry point or the source changed.
 */
export function copyWebOutput(repositoryRoot, input, options) {
  const root = realpathSync(resolve(repositoryRoot))
  const webDist = join(root, 'apps/web/dist')
  const rootDist = join(root, 'dist')
  if (!existsSync(join(webDist, 'index.html')))
    throw new Error('apps/web/dist/index.html not found; generate the web app first')
  const metadata = createBuildInfo(root, input, options)
  // Validate every input before replacing the previous successful deployment output.
  JSON.parse(readFileSync(join(root, 'edgeone.json'), 'utf8'))
  rmSync(rootDist, { recursive: true, force: true })
  cpSync(webDist, rootDist, { recursive: true, dereference: true })
  cpSync(join(root, 'edgeone.json'), join(rootDist, 'edgeone.json'))
  mkdirSync(join(rootDist, '.well-known'), { recursive: true })
  writeFileSync(join(rootDist, BUILD_INFO_PATH), `${JSON.stringify(metadata, null, 2)}\n`)
  return rootDist
}

/**
 * Prepare data without rewriting tracked timestamps, then generate and attest Nuxt assets.
 *
 * @param {string} repositoryRoot Root of the Cook checkout.
 * @param {{ mode?: 'build' | 'full' | 'generate', environment?: NodeJS.ProcessEnv, prepare?: (root: string, args: string[], environment: NodeJS.ProcessEnv) => void, generate?: (root: string, environment: NodeJS.ProcessEnv) => void, now?: () => Date }} [options] Build mode, processes, and clock dependencies.
 * @returns {string} Absolute root dist directory.
 * @throws {Error} If generation or source verification fails; no checkpoint is left behind.
 */
export function buildWebOutput(repositoryRoot, {
  mode = 'generate',
  environment = process.env,
  prepare = (root, args, env) => execFileSync('pnpm', args, { cwd: root, env, stdio: 'inherit' }),
  generate = (root, env) => execFileSync('pnpm', ['-C', 'apps/web', 'run', 'generate'], { cwd: root, env, stdio: 'inherit' }),
  now,
} = {}) {
  if (!['build', 'full', 'generate'].includes(mode))
    throw new Error('Unknown web build mode; expected build, full, or generate')
  const initialInput = captureBuildInput(repositoryRoot, environment)
  const buildEnvironment = { ...environment, COOK_PRESERVE_SOURCE_TIMESTAMP: 'true' }
  if (mode === 'full')
    prepare(repositoryRoot, ['run', 'fetch'], buildEnvironment)
  if (mode !== 'generate')
    prepare(repositoryRoot, ['run', 'convert'], buildEnvironment)
  const preparedInput = captureBuildInput(repositoryRoot, buildEnvironment)
  if (preparedInput.commitSha !== initialInput.commitSha || preparedInput.version !== initialInput.version)
    throw new Error('Build source changed during data preparation; rebuild from a fixed checkout')
  const input = Object.freeze({ ...preparedInput, dirty: initialInput.dirty || preparedInput.dirty })
  generate(repositoryRoot, buildEnvironment)
  return copyWebOutput(repositoryRoot, input, { environment: buildEnvironment, now })
}
