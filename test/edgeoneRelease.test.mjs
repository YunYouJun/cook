import assert from 'node:assert/strict'
// eslint-disable-next-line test/no-import-node-test -- Deployment verification runs without installing application dependencies.
import { test } from 'node:test'
import { EDGEONE_PRODUCTION_TARGETS, verifyEdgeOneRelease } from '../scripts/lib/edgeone-release.mjs'

const sha = 'a'.repeat(40)
const otherSha = 'b'.repeat(40)
const now = '2026-10-28T12:00:00.000Z'
const api = 'https://pages-api.cloud.tencent.com/v1'
const token = 'private-test-token'

function json(value) {
  return new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json; charset=utf-8' } })
}

function apiResponse(value) {
  return json({ Code: 0, Data: { Response: value } })
}

function fixtures() {
  const deployments = EDGEONE_PRODUCTION_TARGETS.map((target, index) => ({
    DeploymentId: `dp-production-${index}`,
    ProjectId: target.projectId,
    Status: 'Success',
    Env: 'Production',
    ViaMeta: 'Github',
    RepoBranch: 'main',
    RepoCommitHash: sha,
    DplRepoUrl: 'https://github.com/YunYouJun/cook',
    UsedInProd: true,
  }))
  const projects = EDGEONE_PRODUCTION_TARGETS.map((target, index) => ({
    ProjectId: target.projectId,
    Provider: 'Github',
    RepoUrl: 'https://github.com/YunYouJun/cook',
    RepoBranch: 'main',
    CustomDomains: [{ Domain: new URL(target.origin).hostname }],
    Deployment: structuredClone(deployments[index]),
    EnvVars: [{ Key: 'PRIVATE_SERVER_TOKEN', Value: 'never-return-this-provider-field' }],
  }))
  return { projects, deployments }
}

function manifest() {
  return { schemaVersion: 1, productId: 'cook', repository: 'YunYouJun/cook', commitSha: sha, version: '2.0.0-beta.18', builtAt: '2026-10-28T11:00:00.000Z', dirty: false }
}

function appHtml(build = sha) {
  return `<html><head><title>食用手册</title></head><body><div id="__nuxt"></div><script>window.__NUXT__={config:{public:{feedbackBuild:"${build}"}}}</script><script type="module" src="/_nuxt/app.js"></script></body></html>`
}

function mockNetwork(intercept = () => undefined) {
  const state = { ...fixtures(), calls: [], projectQueries: 0 }
  const fetch = async (input, options) => {
    const url = new URL(input)
    const body = options.body ? JSON.parse(options.body) : undefined
    state.calls.push({ url, options, body })
    assert.equal(options.redirect, 'error')
    assert.equal(options.credentials, 'omit')
    assert(options.signal instanceof AbortSignal)
    if (url.href !== api) {
      assert.equal(options.headers.Authorization, undefined)
      assert.equal(options.headers['Cache-Control'], 'no-cache, no-store')
    }
    else {
      assert.equal(options.headers.Authorization, `Bearer ${token}`)
      assert.equal(options.method, 'POST')
    }
    if (body?.Action === 'DescribePagesProjects')
      state.projectQueries++
    const intercepted = await intercept({ url, options, body, state })
    if (intercepted !== undefined)
      return intercepted
    if (body?.Action === 'DescribePagesProjects') {
      assert.deepEqual(body.Filters, [{ Name: 'ProjectId', Values: EDGEONE_PRODUCTION_TARGETS.map(target => target.projectId) }])
      return apiResponse({ TotalCount: state.projects.length, Projects: state.projects })
    }
    if (body?.Action === 'DescribePagesDeployments') {
      const deployments = state.deployments.filter(deployment => deployment.ProjectId === body.ProjectId)
      assert.equal(body.Limit, 50)
      assert.equal(body.OrderType, 'Desc')
      assert.equal(body.Order, undefined)
      return apiResponse({ TotalCount: deployments.length, Deployments: deployments.slice(body.Offset * body.Limit, (body.Offset + 1) * body.Limit) })
    }
    if (url.pathname === '/.well-known/cook-build.json') {
      assert(url.searchParams.get('nonce').startsWith(sha))
      return json(manifest())
    }
    if (url.pathname.endsWith('.js'))
      return new Response('export const bootstrapped = true', { headers: { 'Content-Type': 'application/javascript' } })
    if (url.pathname === '/' || url.pathname === '/home') {
      const html = url.origin === 'https://cook.yunle.fun' && url.pathname === '/'
        ? '<html><head><title>云食册 · 食用手册</title><script type="module" src="/assets/product.js"></script></head><body>有什么食材，就做什么菜</body></html>'
        : appHtml()
      return new Response(html, { headers: { 'Content-Type': 'text/html; charset=utf-8' } })
    }
    throw new Error('Unexpected mock request')
  }
  return { fetch, state }
}

function verify(fetch, options = {}) {
  return verifyEdgeOneRelease({ token, expectedSha: sha, fetch, now: () => new Date(now), ...options })
}

test('verifies both current EdgeOne production projects and their own public app entries', async () => {
  const { fetch, state } = mockNetwork()
  const result = await verify(fetch)
  assert.equal(result.provider, 'edgeone')
  assert.equal(result.environment, 'production')
  assert.equal(result.verified, true)
  assert.equal(result.sha, sha)
  assert.equal(result.id, 'edgeone:dp-production-0-dp-production-1')
  assert.equal(result.origin, 'https://cook.yunyoujun.cn')
  assert.equal(result.verifiedAt, now)
  assert.equal(result.targets.length, 2)
  assert.equal(result.evidenceRefs.length, 4)
  assert.equal(state.projectQueries, 2)
  assert(state.calls.some(call => call.url.origin === 'https://cook.yunle.fun' && call.url.pathname === '/home'))
  assert(!state.calls.some(call => call.url.origin === 'https://cook.yunyoujun.cn' && call.url.pathname === '/home'))
  assert(!JSON.stringify(result).includes(token))
  assert(!JSON.stringify(result).includes('never-return-this-provider-field'))
})

test('queries later deployment pages to locate the project current deployment', async () => {
  const { fetch, state } = mockNetwork()
  const current = state.deployments[0]
  state.deployments.unshift(...Array.from({ length: 50 }, (_, index) => ({ ...current, DeploymentId: `dp-preview-${index}`, Env: 'Preview', UsedInProd: false })))
  await verify(fetch)
  assert.deepEqual(state.calls.filter(call => call.body?.Action === 'DescribePagesDeployments' && call.body.ProjectId === current.ProjectId).map(call => call.body.Offset), [0, 1, 0, 1])
})

test('rejects Vercel, preview, wrong branch, another repository or stale production SHA', async () => {
  for (const change of [
    data => data.projects[0].Provider = 'Vercel',
    data => data.projects[0].RepoBranch = 'feature',
    data => data.projects[0].RepoUrl = 'https://github.com/other/cook',
    data => data.deployments[0].Env = 'Preview',
    data => data.deployments[0].ViaMeta = 'Upload',
    data => data.deployments[0].Status = 'Failure',
    data => data.deployments[0].RepoCommitHash = otherSha,
    data => data.deployments[0].DplRepoUrl = 'https://github.com/other/cook',
  ]) {
    const { fetch, state } = mockNetwork()
    change(state)
    await assert.rejects(verify(fetch), { code: 'EDGEONE_RELEASE_UNVERIFIED' })
    assert(!state.calls.some(call => call.url.origin !== new URL(api).origin))
  }
})

test('requires both bound production domains and project records', async () => {
  for (const change of [
    data => data.projects.pop(),
    data => data.projects[0].ProjectId = 'pages-preview',
    data => data.projects[0].CustomDomains = [{ Domain: 'preview.example.com' }],
    data => data.projects[1] = structuredClone(data.projects[0]),
  ]) {
    const { fetch, state } = mockNetwork()
    change(state)
    await assert.rejects(verify(fetch), { code: 'EDGEONE_RELEASE_UNVERIFIED' })
  }
})

test('does not accept an old successful deployment absent from current production', async () => {
  const { fetch, state } = mockNetwork()
  state.deployments[0].UsedInProd = false
  await assert.rejects(verify(fetch), { reason: 'current_deployment_missing' })
  state.deployments[0].UsedInProd = true
  state.deployments[0].Env = 'Preview'
  await assert.rejects(verify(fetch), { reason: 'deployment_not_production_success' })
})

test('rejects mismatched, dirty, unversioned, future, extended or malformed manifests', async () => {
  for (const modify of [
    value => value.commitSha = otherSha,
    value => value.dirty = true,
    value => value.dirty = 'false',
    value => value.schemaVersion = 2,
    value => value.productId = 'other',
    value => value.repository = 'other/cook',
    value => value.version = '',
    value => value.builtAt = 'invalid-time',
    value => value.builtAt = '2026-11-01T00:00:00.000Z',
    value => value.privateToken = token,
  ]) {
    const { fetch } = mockNetwork(({ url }) => {
      if (url.pathname !== '/.well-known/cook-build.json')
        return
      const value = manifest()
      modify(value)
      return json(value)
    })
    await assert.rejects(verify(fetch), { code: 'EDGEONE_RELEASE_UNVERIFIED' })
  }
  const { fetch } = mockNetwork(({ url }) => url.pathname === '/.well-known/cook-build.json' ? new Response('{', { headers: { 'Content-Type': 'application/json' } }) : undefined)
  await assert.rejects(verify(fetch), { reason: 'manifest_json_invalid' })
})

test('rejects a stale app HTML shell even when the manifest is current', async () => {
  const { fetch } = mockNetwork(({ url }) => url.origin === 'https://cook.yunle.fun' && url.pathname === '/home' ? new Response(appHtml(otherSha), { headers: { 'Content-Type': 'text/html' } }) : undefined)
  await assert.rejects(verify(fetch), { reason: 'html_build_mismatch' })
})

test('rejects missing app shells, error HTML, failed assets and HTML masquerading as JavaScript', async () => {
  for (const intercept of [
    ({ url }) => url.pathname === '/home' ? new Response('<html><body>unavailable</body></html>', { headers: { 'Content-Type': 'text/html' } }) : undefined,
    ({ url }) => url.pathname === '/home' ? new Response('<html><title>503 Unavailable</title></html>', { headers: { 'Content-Type': 'text/html' } }) : undefined,
    ({ url }) => url.pathname.endsWith('.js') ? new Response('missing', { status: 404 }) : undefined,
    ({ url }) => url.pathname.endsWith('.js') ? new Response('<html>error</html>', { headers: { 'Content-Type': 'application/javascript' } }) : undefined,
    ({ url }) => url.pathname.endsWith('.js') ? new Response('server error', { headers: { 'Content-Type': 'text/html' } }) : undefined,
  ]) {
    const { fetch } = mockNetwork(intercept)
    await assert.rejects(verify(fetch), { code: 'EDGEONE_RELEASE_UNVERIFIED' })
  }
})

test('rejects redirects, provider API errors and network failures without exposing raw errors', async () => {
  for (const intercept of [
    ({ url }) => url.href === api ? Response.redirect('https://untrusted.example.com', 302) : undefined,
    ({ url }) => url.pathname === '/.well-known/cook-build.json' ? Response.redirect('https://preview.example.com', 302) : undefined,
    ({ url }) => url.href === api ? json({ Code: 109, Message: `secret ${token}` }) : undefined,
    () => { throw new Error(`Do not expose Bearer ${token}`) },
  ]) {
    const { fetch } = mockNetwork(intercept)
    await assert.rejects(verify(fetch), (error) => {
      assert.equal(error.code, 'EDGEONE_RELEASE_UNVERIFIED')
      assert(!error.message.includes(token))
      return true
    })
  }
})

test('requires both production manifests to describe the same package version', async () => {
  const { fetch } = mockNetwork(({ url }) => url.origin === 'https://cook.yunle.fun' && url.pathname === '/.well-known/cook-build.json' ? json({ ...manifest(), version: '9.0.0' }) : undefined)
  await assert.rejects(verify(fetch), { reason: 'production_version_mismatch' })
})

test('ignores a newer preview in the project overview and deployment list', async () => {
  const { fetch, state } = mockNetwork()
  const preview = { ...state.deployments[0], DeploymentId: 'dp-preview', Env: 'Preview', UsedInProd: false, RepoCommitHash: otherSha }
  state.projects[0].Deployment = preview
  state.deployments.unshift(preview)
  assert.equal((await verify(fetch)).targets[0].deploymentId, 'dp-production-0')
})

test('rejects ambiguous production pointers', async () => {
  const { fetch, state } = mockNetwork()
  state.deployments.push({ ...state.deployments[0], DeploymentId: 'dp-other' })
  await assert.rejects(verify(fetch), { reason: 'deployment_ambiguous' })
})

test('rechecks authoritative production pointers after assets', async () => {
  const { fetch } = mockNetwork(({ url, state }) => {
    if (url.origin === 'https://cook.yunle.fun' && url.pathname === '/home')
      state.deployments[0].DeploymentId = 'dp-production-replaced'
  })
  await assert.rejects(verify(fetch), { reason: 'production_changed_during_verification' })
})

test('rejects incomplete or changing deployment pagination', async () => {
  const { fetch, state } = mockNetwork(({ body }) => {
    if (body?.Action === 'DescribePagesDeployments' && body.Offset === 1)
      return apiResponse({ TotalCount: 52, Deployments: [] })
  })
  state.deployments.unshift(...Array.from({ length: 50 }, (_, index) => ({ ...state.deployments[0], DeploymentId: `dp-preview-${index}`, Env: 'Preview', UsedInProd: false })))
  await assert.rejects(verify(fetch), { code: 'EDGEONE_RELEASE_UNVERIFIED' })
})

test('rejects invalid SHA, missing token or clock before sending requests', async () => {
  for (const options of [{ expectedSha: 'main' }, { expectedSha: 'A'.repeat(40) }, { token: '' }, { token: 'injected\r\nheader' }, { now }, { now: () => new Date(Number.NaN) }, { now: () => now }]) {
    const { fetch, state } = mockNetwork()
    await assert.rejects(verify(fetch, options), { code: 'EDGEONE_RELEASE_UNVERIFIED' })
    assert.equal(state.calls.length, 0)
  }
})
