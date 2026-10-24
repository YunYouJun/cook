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
}

export function detectDocsOnly(eventName, event, cwd = process.cwd()) {
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
      docsOnly: paths.length > 0 && paths.every(isDocumentation),
      changedFiles: paths.length,
      comparison: `${base}${separator}${head}`,
    }
  }
  catch {
    // An unavailable base (including a force push/new branch) must never skip tests.
    return { docsOnly: false, changedFiles: null, comparison: 'unavailable' }
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
  const result = detectDocsOnly(process.env.GITHUB_EVENT_NAME, event)
  if (process.env.GITHUB_OUTPUT)
    appendFileSync(process.env.GITHUB_OUTPUT, `docs_only=${result.docsOnly}\n`)
  if (process.env.GITHUB_STEP_SUMMARY)
    appendFileSync(process.env.GITHUB_STEP_SUMMARY, `Native CI: ${result.docsOnly ? 'skipped (documentation only)' : 'required'}. Changed paths: ${result.changedFiles ?? 'unknown'}. Comparison: ${result.comparison}.\n`)
  console.log(JSON.stringify(result))
}
