import { Buffer } from 'node:buffer'
import { randomUUID } from 'node:crypto'

const EDGEONE_API = 'https://pages-api.cloud.tencent.com/v1'
const REPOSITORY = 'YunYouJun/cook'
const REPOSITORY_URL = `https://github.com/${REPOSITORY}`
const MANIFEST_PATH = '/.well-known/cook-build.json'
const SHA = /^[a-f0-9]{40}$/
const MANIFEST_KEYS = ['builtAt', 'commitSha', 'dirty', 'productId', 'repository', 'schemaVersion', 'version']

/** Fixed production projects; previews and other deployment providers are excluded. */
export const EDGEONE_PRODUCTION_TARGETS = Object.freeze([
  Object.freeze({ projectId: 'pages-d3ggorpihuk6', origin: 'https://cook.yunyoujun.cn' }),
  Object.freeze({ projectId: 'pages-izqbljwk91wt', origin: 'https://cook.yunle.fun' }),
])

/** A safe diagnostic code, without provider responses, credentials, or user content. */
export class EdgeOneReleaseError extends Error {
  constructor(reason) {
    super(`EdgeOne release verification failed: ${reason}`)
    this.name = 'EdgeOneReleaseError'
    this.code = 'EDGEONE_RELEASE_UNVERIFIED'
    this.reason = reason
  }
}

function requireCondition(condition, reason) {
  if (!condition)
    throw new EdgeOneReleaseError(reason)
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

async function readBody(response, limit, reason) {
  try {
    const chunks = []
    let size = 0
    if (response.body) {
      for await (const chunk of response.body) {
        size += chunk.byteLength
        requireCondition(size <= limit, reason)
        chunks.push(chunk)
      }
    }
    return Buffer.concat(chunks).toString('utf8')
  }
  catch (error) {
    if (error instanceof EdgeOneReleaseError)
      throw error
    throw new EdgeOneReleaseError(reason)
  }
}

async function request(fetcher, url, options, kind) {
  let response
  try {
    response = await fetcher(url, { ...options, redirect: 'error', credentials: 'omit', signal: AbortSignal.timeout(15_000) })
  }
  catch {
    throw new EdgeOneReleaseError(`${kind}_network_failed`)
  }
  requireCondition(response?.status === 200 && response.redirected !== true, `${kind}_http_failed`)
  if (response.url) {
    const requested = new URL(url)
    let actual
    try {
      actual = new URL(response.url)
    }
    catch {
      throw new EdgeOneReleaseError(`${kind}_response_url_invalid`)
    }
    requireCondition(actual.origin === requested.origin && actual.pathname === requested.pathname, `${kind}_response_url_mismatch`)
  }
  return response
}

async function jsonResponse(response, kind, limit = 256 * 1024) {
  requireCondition(response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase() === 'application/json', `${kind}_content_type_invalid`)
  const text = await readBody(response, limit, `${kind}_body_invalid`)
  try {
    return JSON.parse(text)
  }
  catch {
    throw new EdgeOneReleaseError(`${kind}_json_invalid`)
  }
}

async function queryEdgeOne(fetcher, token, body) {
  const response = await request(fetcher, EDGEONE_API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${token}` },
    body: JSON.stringify(body),
  }, 'edgeone')
  const result = await jsonResponse(response, 'edgeone')
  requireCondition(isRecord(result) && result.Code === 0 && isRecord(result.Data?.Response), 'edgeone_api_failed')
  return result.Data.Response
}

function productionDeployment(deployment, projectId, expectedSha, requireCurrent = false) {
  requireCondition(isRecord(deployment), 'deployment_missing')
  requireCondition(deployment.ProjectId === projectId, 'deployment_project_mismatch')
  requireCondition(typeof deployment.DeploymentId === 'string' && /^[\w-]{1,128}$/.test(deployment.DeploymentId), 'deployment_id_invalid')
  requireCondition(deployment.Status === 'Success' && deployment.Env === 'Production', 'deployment_not_production_success')
  requireCondition(deployment.ViaMeta === 'Github' && deployment.DplRepoUrl === REPOSITORY_URL && deployment.RepoBranch === 'main', 'deployment_source_mismatch')
  requireCondition(deployment.RepoCommitHash === expectedSha, 'deployment_sha_mismatch')
  if (requireCurrent)
    requireCondition(deployment.UsedInProd === true, 'deployment_not_current')
  return deployment
}

async function projectsSnapshot(query, targets, expectedSha) {
  const result = await query({
    Action: 'DescribePagesProjects',
    Filters: [{ Name: 'ProjectId', Values: targets.map(target => target.projectId) }],
    Offset: 0,
    Limit: 100,
  })
  requireCondition(Array.isArray(result.Projects) && result.Projects.length === targets.length && result.TotalCount === targets.length, 'production_project_missing')
  return targets.map((target) => {
    const matches = result.Projects.filter(project => project?.ProjectId === target.projectId)
    requireCondition(matches.length === 1, 'production_project_missing')
    const project = matches[0]
    requireCondition(project.Provider === 'Github' && project.RepoUrl === REPOSITORY_URL && project.RepoBranch === 'main', 'project_source_mismatch')
    requireCondition(Array.isArray(project.CustomDomains) && project.CustomDomains.some(domain => domain?.Domain === new URL(target.origin).hostname), 'production_domain_mismatch')
    const deployment = productionDeployment(project.Deployment, target.projectId, expectedSha)
    return { ...target, deploymentId: deployment.DeploymentId }
  })
}

async function assertCurrentDeployment(query, target, expectedSha) {
  for (let offset = 0; offset < 1000; offset += 50) {
    const result = await query({ Action: 'DescribePagesDeployments', ProjectId: target.projectId, Offset: offset, Limit: 50, OrderBy: 'CreatedOn', Order: 'Desc' })
    requireCondition(Array.isArray(result.Deployments) && Number.isSafeInteger(result.TotalCount) && result.TotalCount >= 0, 'deployments_response_invalid')
    const current = result.Deployments.filter(deployment => deployment?.DeploymentId === target.deploymentId)
    requireCondition(current.length <= 1, 'deployment_ambiguous')
    if (current.length) {
      productionDeployment(current[0], target.projectId, expectedSha, true)
      return
    }
    if (!result.Deployments.length || offset + result.Deployments.length >= result.TotalCount)
      break
  }
  throw new EdgeOneReleaseError('current_deployment_missing')
}

function uncachedHeaders(accept) {
  return { 'Accept': accept, 'Cache-Control': 'no-cache, no-store', 'Pragma': 'no-cache' }
}

async function verifyManifest(fetcher, target, expectedSha, verifiedAt, nonce) {
  const url = new URL(MANIFEST_PATH, target.origin)
  url.searchParams.set('nonce', nonce)
  const response = await request(fetcher, url.href, { headers: uncachedHeaders('application/json') }, 'manifest')
  const manifest = await jsonResponse(response, 'manifest', 4096)
  requireCondition(isRecord(manifest) && Object.keys(manifest).sort().join(',') === MANIFEST_KEYS.join(','), 'manifest_schema_invalid')
  requireCondition(manifest.schemaVersion === 1 && manifest.productId === 'cook' && manifest.repository === REPOSITORY, 'manifest_identity_mismatch')
  requireCondition(typeof manifest.commitSha === 'string' && SHA.test(manifest.commitSha) && manifest.commitSha === expectedSha, 'manifest_sha_mismatch')
  requireCondition(manifest.dirty === false, 'manifest_dirty')
  requireCondition(typeof manifest.version === 'string' && /^[\w.+-]{1,128}$/.test(manifest.version), 'manifest_version_invalid')
  let builtAt
  try {
    builtAt = typeof manifest.builtAt === 'string' ? new Date(manifest.builtAt).toISOString() : undefined
  }
  catch {
    throw new EdgeOneReleaseError('manifest_timestamp_invalid')
  }
  requireCondition(builtAt === manifest.builtAt && Date.parse(builtAt) <= Date.parse(verifiedAt) + 5 * 60 * 1000, 'manifest_timestamp_invalid')
  return { version: manifest.version, builtAt }
}

function moduleScripts(html, pageUrl, application) {
  const scripts = []
  for (const match of html.matchAll(/<script\b[^>]*>/gi)) {
    const source = match[0].match(/\bsrc\s*=\s*(["'])(.*?)\1/i)?.[2]
    const type = match[0].match(/\btype\s*=\s*(["'])(.*?)\1/i)?.[2]
    if (!source || type?.toLowerCase() !== 'module')
      continue
    let url
    try {
      url = new URL(source, pageUrl)
    }
    catch {
      throw new EdgeOneReleaseError('html_asset_url_invalid')
    }
    if (url.origin === new URL(pageUrl).origin && (application ? url.pathname.startsWith('/_nuxt/') : url.pathname.startsWith('/assets/')) && url.pathname.endsWith('.js'))
      scripts.push(url.href)
  }
  requireCondition(scripts.length > 0 && scripts.length <= 12, 'html_application_asset_missing')
  return [...new Set(scripts)]
}

async function verifyPage(fetcher, target, path, application, expectedSha, nonce) {
  const url = new URL(path, target.origin)
  url.searchParams.set('cook-release-check', nonce)
  const response = await request(fetcher, url.href, { headers: uncachedHeaders('text/html') }, 'html')
  requireCondition(response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase() === 'text/html', 'html_content_type_invalid')
  const html = await readBody(response, 2 * 1024 * 1024, 'html_body_invalid')
  requireCondition(/<html\b/i.test(html) && !/<vite-error-overlay\b|<nuxt-error-overlay\b|<title>\s*(?:404|500|502|503)\b/i.test(html), 'html_invalid')
  if (application) {
    requireCondition(/\bid\s*=\s*(["'])__nuxt\1/i.test(html) && html.includes('__NUXT__'), 'html_app_shell_missing')
    const sha = html.match(/["']?feedbackBuild["']?\s*:\s*(["'])([a-f0-9]{40})\1/)?.[2]
    requireCondition(sha === expectedSha, 'html_build_mismatch')
  }
  for (const asset of moduleScripts(html, url.href, application)) {
    const js = await request(fetcher, asset, { headers: uncachedHeaders('text/javascript, application/javascript') }, 'asset')
    const contentType = js.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase()
    requireCondition(['application/javascript', 'text/javascript', 'application/ecmascript', 'text/ecmascript'].includes(contentType), 'asset_content_type_invalid')
    const content = await readBody(js, 5 * 1024 * 1024, 'asset_body_invalid')
    requireCondition(content.trim().length > 0 && !/^\s*(?:<!doctype|<html\b)/i.test(content), 'asset_invalid')
  }
}

/**
 * Verify both current EdgeOne production deployments against their public build manifests.
 * This checks deployment identity, application HTML and entry assets; browser regression
 * evidence is a separate gate and must not be inferred from this result.
 *
 * @param {object} options Verification inputs and injectable network/clock dependencies.
 * @param {string} options.token EdgeOne API token, used only with the fixed API origin.
 * @param {string} options.expectedSha Full lowercase SHA expected on both production sites.
 * @param {typeof globalThis.fetch} [options.fetch] HTTP implementation; defaults to the runtime fetch.
 * @param {() => Date} [options.now] Verification clock, injectable for deterministic tests.
 * @returns {Promise<object>} Public, bounded production evidence without secrets or private payloads.
 * @throws {EdgeOneReleaseError} If any deployment, version, HTTP or asset check fails.
 */
export async function verifyEdgeOneRelease({ token, expectedSha, fetch: fetcher = globalThis.fetch, now = () => new Date() }) {
  requireCondition(typeof token === 'string' && token.trim().length > 0 && token.length <= 4096 && !/[\r\n]/.test(token), 'token_required')
  requireCondition(typeof expectedSha === 'string' && SHA.test(expectedSha), 'expected_sha_invalid')
  requireCondition(typeof fetcher === 'function', 'fetch_required')
  let verifiedAt
  try {
    verifiedAt = now().toISOString()
  }
  catch {
    throw new EdgeOneReleaseError('verification_timestamp_invalid')
  }
  const allowed = EDGEONE_PRODUCTION_TARGETS
  const query = body => queryEdgeOne(fetcher, token, body)
  const snapshot = await projectsSnapshot(query, allowed, expectedSha)
  const nonce = `${expectedSha}-${randomUUID()}`
  const results = []
  for (const target of snapshot) {
    await assertCurrentDeployment(query, target, expectedSha)
    const manifest = await verifyManifest(fetcher, target, expectedSha, verifiedAt, nonce)
    await verifyPage(fetcher, target, '/', target.origin === 'https://cook.yunyoujun.cn', expectedSha, nonce)
    if (target.origin === 'https://cook.yunle.fun')
      await verifyPage(fetcher, target, '/home', true, expectedSha, nonce)
    results.push({ ...target, sha: expectedSha, ...manifest })
  }
  requireCondition(new Set(results.map(target => target.version)).size === 1, 'production_version_mismatch')
  const finalSnapshot = await projectsSnapshot(query, allowed, expectedSha)
  requireCondition(finalSnapshot.every((target, index) => target.deploymentId === snapshot[index].deploymentId), 'production_changed_during_verification')
  return {
    provider: 'edgeone',
    environment: 'production',
    verified: true,
    sha: expectedSha,
    id: `edgeone:${results.map(target => target.deploymentId).join('-')}`,
    origin: allowed[0].origin,
    verifiedAt,
    summary: '两个 EdgeOne 生产项目、线上构建版本、应用入口与启动资源均已核对；浏览器回归需另附证据。',
    evidenceRefs: results.flatMap(target => [
      `${target.origin}${MANIFEST_PATH}`,
      `edgeone:${target.projectId}/${target.deploymentId}@${expectedSha}`,
    ]),
    targets: results,
  }
}
