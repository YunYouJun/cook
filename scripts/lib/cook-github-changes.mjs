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

/** Require all Web jobs from the latest exact-main CI push run and its current attempt. */
export async function verifyCookChecks(sha, { token, fetch: send = globalThis.fetch } = {}) {
  if (!shaPattern.test(sha))
    invalid()
  async function get(path) {
    try {
      const response = await send(`https://api.github.com/repos/${repository}/${path}`, { redirect: 'error', signal: AbortSignal.timeout(15_000), headers: { accept: 'application/vnd.github+json', ...(token ? { authorization: `Bearer ${token}` } : {}) } })
      if (!response.ok)
        invalid()
      return await response.json()
    }
    catch { invalid() }
  }
  const history = await get(`actions/workflows/ci.yml/runs?event=push&branch=main&head_sha=${sha}&per_page=1`)
  if (!Array.isArray(history?.workflow_runs) || !Number.isSafeInteger(history.total_count) || history.total_count < 1 || history.workflow_runs.length !== 1)
    invalid()
  const run = history.workflow_runs[0]
  if (!Number.isSafeInteger(run.id) || run.id < 1 || !Number.isSafeInteger(run.run_attempt) || run.run_attempt < 1 || run.path !== '.github/workflows/ci.yml' || run.event !== 'push' || run.head_branch !== 'main' || run.head_sha !== sha || run.repository?.full_name !== repository || run.head_repository?.full_name !== repository)
    invalid()
  const jobs = []
  let total
  for (let page = 1; page <= 100; page++) {
    const result = await get(`actions/runs/${run.id}/attempts/${run.run_attempt}/jobs?per_page=100&page=${page}`)
    if (!Number.isSafeInteger(result?.total_count) || result.total_count < 0 || !Array.isArray(result.jobs) || result.jobs.length > 100 || (total !== undefined && total !== result.total_count))
      invalid()
    total = result.total_count
    jobs.push(...result.jobs)
    if (result.jobs.length < 100)
      break
  }
  if (jobs.length !== total || jobs.some(job => job.run_id !== run.id || job.run_attempt !== run.run_attempt || job.head_sha !== sha))
    invalid()
  for (const name of ['lint', 'typecheck', 'build', 'test', 'web-acceptance']) {
    const matching = jobs.filter(job => job.name === name || (name === 'test' && job.name?.startsWith('test (')))
    if (!matching.length || matching.some(job => job.status !== 'completed' || job.conclusion !== 'success'))
      throw Object.assign(new Error('Main revision checks have not passed'), { code: 'CHECKS_NOT_READY' })
  }
  return `https://github.com/${repository}/actions/runs/${run.id}`
}
