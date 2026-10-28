const repository = 'YunYouJun/cook'
const shaPattern = /^[a-f\d]{40}$/
const datePattern = /^\d{4}-\d\d-\d\dT/

function invalid() {
  throw Object.assign(new Error('GitHub change evidence is unavailable or invalid'), { code: 'INVALID_GITHUB_EVIDENCE' })
}

/**
 * Resolve only explicitly bound public development references, without trusting PR text or executing PR code.
 * @param {Array} bindings Validated private bindings.
 * @param {{token?:string,fetch?:typeof fetch}} options GitHub read credential and HTTP port.
 * @returns {Promise<{sha:string,events:Array,verifiedChangeShas:string[]}>} Main revision and public change evidence.
 */
export async function collectCookGitHubChanges(bindings, { token, fetch: send = globalThis.fetch } = {}) {
  const cache = new Map()
  async function get(path) {
    if (!cache.has(path)) {
      cache.set(path, (async () => {
        let response
        try {
          response = await send(`https://api.github.com/repos/${repository}/${path}`, { redirect: 'error', signal: AbortSignal.timeout(15_000), headers: { 'accept': 'application/vnd.github+json', 'x-github-api-version': '2022-11-28', ...(token ? { authorization: `Bearer ${token}` } : {}) } })
        }
        catch { invalid() }
        if (!response.ok)
          invalid()
        try {
          return await response.json()
        }
        catch { invalid() }
      })())
    }
    return cache.get(path)
  }
  const main = await get('commits/main')
  if (!shaPattern.test(main?.sha))
    invalid()
  const events = new Map()
  const candidates = new Set()
  for (const binding of bindings) {
    if (binding.repository !== repository)
      invalid()
    for (const sha of binding.commitShas) {
      if (!shaPattern.test(sha))
        invalid()
      const commit = await get(`commits/${sha}`)
      const date = commit?.commit?.committer?.date
      if (commit?.sha !== sha || typeof date !== 'string' || !datePattern.test(date) || !Number.isFinite(Date.parse(date)))
        invalid()
      const eventKey = `github-commit-${sha}`
      events.set(eventKey, { schemaVersion: 1, repository, kind: 'change', eventKey, occurredAt: date, commitSha: sha, refs: [`https://github.com/${repository}/commit/${sha}`] })
      candidates.add(sha)
    }
    for (const number of binding.pullRequests) {
      if (!Number.isSafeInteger(number) || number < 1)
        invalid()
      const pull = await get(`pulls/${number}`)
      if (pull?.number !== number || pull.base?.repo?.full_name !== repository || pull.base.ref !== 'main' || !shaPattern.test(pull.head?.sha) || typeof pull.created_at !== 'string' || !Number.isFinite(Date.parse(pull.created_at)))
        invalid()
      const sha = pull.merged ? pull.merge_commit_sha : pull.head.sha
      if (!shaPattern.test(sha))
        invalid()
      const eventKey = `github-pr-${number}-${sha}`
      events.set(eventKey, { schemaVersion: 1, repository, kind: 'change', eventKey, occurredAt: pull.created_at, commitSha: sha, refs: [`https://github.com/${repository}/pull/${number}`, `https://github.com/${repository}/commit/${sha}`] })
      if (pull.merged)
        candidates.add(sha)
    }
  }
  const verifiedChangeShas = []
  for (const sha of candidates) {
    const comparison = await get(`compare/${sha}...${main.sha}`)
    if (!['ahead', 'behind', 'identical', 'diverged'].includes(comparison?.status) || comparison.base_commit?.sha !== sha || comparison.commits?.some(commit => !shaPattern.test(commit?.sha)))
      invalid()
    if (['ahead', 'identical'].includes(comparison.status) && comparison.merge_base_commit?.sha === sha)
      verifiedChangeShas.push(sha)
  }
  return { sha: main.sha, events: [...events.values()], verifiedChangeShas }
}

/** Require the actual main revision's build, lint, typecheck and test jobs before production acceptance. */
export async function verifyCookChecks(sha, { token, fetch: send = globalThis.fetch } = {}) {
  if (!shaPattern.test(sha))
    invalid()
  let response
  try {
    response = await send(`https://api.github.com/repos/${repository}/commits/${sha}/check-runs?per_page=100`, { redirect: 'error', signal: AbortSignal.timeout(15_000), headers: { accept: 'application/vnd.github+json', ...(token ? { authorization: `Bearer ${token}` } : {}) } })
  }
  catch { invalid() }
  if (!response.ok)
    invalid()
  const result = await response.json()
  if (!Array.isArray(result?.check_runs))
    invalid()
  for (const name of ['lint', 'typecheck', 'build', 'test']) {
    const check = result.check_runs.find(item => item.app?.slug === 'github-actions' && item.head_sha === sha && (item.name === name || (name === 'test' && item.name?.startsWith('test ('))))
    if (!check || check.status !== 'completed' || check.conclusion !== 'success')
      throw Object.assign(new Error('Main revision checks have not passed'), { code: 'CHECKS_NOT_READY' })
  }
  return `https://github.com/${repository}/commit/${sha}/checks`
}
