import { execFileSync } from 'node:child_process'
import { appendFileSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const sha = /^[\da-f]{40}$/
const workflow = '.github/workflows/ci.yml'
const prefix = 'Verified iOS core'

// A receipt is the name of a completed workflow step in GitHub's job metadata.
// Do not infer success from a green aggregate (a skipped matrix can be green).
function receipt(jobs, device, run, pr) {
  const matches = jobs.filter(job => job.name === `ios-tests (${device}, core-regression)`)
  const job = matches[0]
  if (matches.length !== 1 || job.run_id !== run.id || job.run_attempt !== run.run_attempt
    || job.status !== 'completed' || job.conclusion !== 'success' || !job.labels.includes('xcode-27')) {
    return
  }
  const passed = job.steps.filter(step => step.status === 'completed' && step.conclusion === 'success')
  if (!['Build for testing', 'Build and test'].every(name => passed.some(step => step.name === name)))
    return
  const marks = passed.map(step => step.name).filter(name => name.startsWith(`${prefix} `))
  if (marks.length !== 1)
    return
  const mark = /^Verified iOS core #(\d+) ([\da-f]{40}) ([\da-f]{64})$/.exec(marks[0])
  if (mark && Number(mark[1]) === pr.number)
    return { checkout: mark[2], key: mark[3] }
}

function storekitReceipt(jobs, run) {
  const matches = jobs.filter(job => job.name === 'ios-storekit (iphone)')
  const job = matches[0]
  if (matches.length !== 1 || job.run_id !== run.id || job.run_attempt !== run.run_attempt
    || job.status !== 'completed' || job.conclusion !== 'success' || !job.labels.includes('xcode-27')) {
    return false
  }
  return job.steps.some(step =>
    step.name === 'Verify local StoreKit contracts' && step.status === 'completed' && step.conclusion === 'success')
}

export function findCoreRun({ eventName, event, repo, checkout, parent, tree, api }) {
  const branch = event?.ref?.slice('refs/heads/'.length)
  if (eventName !== 'push' || !['main', 'dev'].includes(branch) || event.ref !== `refs/heads/${branch}`
    || event.forced || event.deleted || event.created || !sha.test(checkout) || !sha.test(tree)
    || event.after !== checkout || !sha.test(parent) || event.before !== parent) {
    return
  }
  const prs = api(`commits/${checkout}/pulls`).filter(pr =>
    pr.merged_at && pr.merge_commit_sha === checkout && pr.base.ref === branch
    && pr.base.repo.full_name === repo && pr.head.repo?.full_name === repo && sha.test(pr.head.sha))
  if (prs.length !== 1)
    return
  const pr = prs[0]
  // Never pick an older passing run behind a failed or pending latest run.
  const [run] = api('actions/workflows/ci.yml/runs', { event: 'pull_request', head_sha: pr.head.sha, per_page: '1' }).workflow_runs
  if (!run || run.path !== workflow || run.event !== 'pull_request' || run.head_sha !== pr.head.sha
    || run.head_branch !== pr.head.ref || run.head_repository?.full_name !== repo || run.repository?.full_name !== repo
    || run.status !== 'completed' || run.conclusion !== 'success'
    || !Number.isSafeInteger(run.id) || !Number.isSafeInteger(run.run_attempt) || run.run_attempt < 1) {
    return
  }
  const { total_count: count, jobs } = api(`actions/runs/${run.id}/attempts/${run.run_attempt}/jobs`, { per_page: '100' })
  if (count !== jobs.length)
    return
  const phone = receipt(jobs, 'iphone', run, pr)
  const pad = receipt(jobs, 'ipad', run, pr)
  if (!phone || !pad || phone.checkout !== pad.checkout || phone.key !== pad.key || !storekitReceipt(jobs, run))
    return
  const tested = api(`git/commits/${phone.checkout}`)
  if (tested.sha !== phone.checkout || tested.tree.sha !== tree || tested.parents.length !== 2
    || tested.parents[0].sha !== parent || tested.parents[1].sha !== pr.head.sha) {
    return
  }
  return { key: phone.key, runId: run.id, pr: pr.number, attempt: run.run_attempt }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const env = process.env
    const git = ref => execFileSync('git', ['rev-parse', ref], { encoding: 'utf8' }).trim()
    const result = findCoreRun({
      eventName: env.GITHUB_EVENT_NAME,
      event: JSON.parse(readFileSync(env.GITHUB_EVENT_PATH, 'utf8')),
      repo: env.GITHUB_REPOSITORY,
      checkout: git('HEAD'),
      parent: git('HEAD^1'),
      tree: git('HEAD^{tree}'),
      api(path, fields = {}) {
        const args = ['api', `repos/${env.GITHUB_REPOSITORY}/${path}`, '-X', 'GET']
        for (const [key, value] of Object.entries(fields))
          args.push('-f', `${key}=${value}`)
        return JSON.parse(execFileSync('gh', args, { encoding: 'utf8', timeout: 10000, stdio: ['ignore', 'pipe', 'pipe'] }))
      },
    })
    if (result) {
      appendFileSync(env.GITHUB_OUTPUT, `key=${result.key}\nrun_id=${result.runId}\n`)
      console.log(`PR #${result.pr}, run ${result.runId} attempt ${result.attempt}: core and StoreKit jobs passed. Checking the current toolchain.`)
    }
    else {
      console.log('No matching PR core and StoreKit results; run the iOS matrix.')
    }
  }
  catch {
    console.log('Could not verify GitHub merge evidence; run the iOS matrix.')
  }
}
