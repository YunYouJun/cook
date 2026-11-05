import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { execFileSync, spawnSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { request } from 'node:http'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import process from 'node:process'
import { setTimeout } from 'node:timers/promises'

const image = process.argv[2]
if (!image)
  throw new Error('Usage: node scripts/test-docker.mjs IMAGE')

const id = `cook-docker-${randomUUID()}`
const app = `${id}-app`
const proxy = `${id}-proxy`
const temporary = mkdtempSync(join(tmpdir(), 'cook-docker-'))
const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', timeout: 120_000 }).trim()
let networkCreated = false

// Use node:http so the domain Host is actually sent, including on current Node fetch implementations.
async function fetchPage(url, { headers, redirect = 'follow' } = {}, remaining = 5) {
  const response = await new Promise((resolve, reject) => {
    const outgoing = request(url, { headers }, (incoming) => {
      const chunks = []
      incoming.on('data', chunk => chunks.push(chunk))
      incoming.on('error', reject)
      incoming.on('end', () => resolve(new Response(Buffer.concat(chunks), {
        status: incoming.statusCode,
        headers: incoming.headers,
      })))
    })
    outgoing.setTimeout(2000, () => outgoing.destroy(new Error(`Request timed out: ${url}`)))
    outgoing.on('error', reject)
    outgoing.end()
  })
  if (redirect === 'follow' && [301, 302, 303, 307, 308].includes(response.status)) {
    assert.ok(remaining > 0, `Too many redirects: ${url}`)
    return fetchPage(new URL(response.headers.get('location'), url), { headers, redirect }, remaining - 1)
  }
  return response
}

async function ready(container) {
  const port = docker('inspect', '--format', '{{(index (index .NetworkSettings.Ports "80/tcp") 0).HostPort}}', container)
  const origin = `http://127.0.0.1:${port}`
  for (let attempt = 0; attempt < 100; attempt++) {
    try {
      const response = await fetchPage(origin, { headers: { Host: 'cook.example.test' } })
      if (response.ok)
        return origin
    }
    catch {}
    await setTimeout(100)
  }
  throw new Error(`Container did not become ready: ${container}`)
}

async function check(origin) {
  const headers = { Host: 'cook.example.test' }
  const root = await fetchPage(`${origin}/`, { headers })
  assert.equal(root.status, 200)
  const html = await root.text()
  assert.match(html, /id="__nuxt"/)

  for (const path of ['/recipes/favorites', '/cookbooks/new', '/auth/callback?ticket=test', '/recipes/history']) {
    const response = await fetchPage(`${origin}${path}`, { headers })
    assert.equal(response.status, 200, path)
    assert.match(response.headers.get('content-type'), /text\/html/)
    assert.match(await response.text(), /id="__nuxt"/, path)
  }

  // /home is a prerendered directory: its slash redirect must stay on the proxy origin.
  const redirect = await fetchPage(`${origin}/home`, { headers, redirect: 'manual' })
  assert.equal(redirect.status, 301)
  assert.equal(redirect.headers.get('location'), '/home/')

  const assetPath = html.match(/src="(\/_nuxt\/[^"?]+\.js)"/)?.[1]
  assert.ok(assetPath, 'generated entry script')
  const asset = await fetchPage(`${origin}${assetPath}`, { headers })
  assert.equal(asset.status, 200)
  assert.match(asset.headers.get('content-type'), /javascript/)
  const script = await asset.text()
  assert.ok(script.length > 0)
  assert.doesNotMatch(script, /<!DOCTYPE html>/i)

  const missing = await fetchPage(`${origin}/_nuxt/nonexistent-smoke-test.js`, { headers })
  assert.equal(missing.status, 404)
  assert.doesNotMatch(await missing.text(), /id="__nuxt"/)

  const favicon = await fetchPage(`${origin}/favicon.svg`, { headers })
  assert.equal(favicon.status, 200)
  assert.match(favicon.headers.get('content-type'), /image\/svg\+xml/)

  const response = await fetchPage(`${origin}/.well-known/cook-build.json`, { headers })
  assert.equal(response.status, 200)
  const build = await response.json()
  assert.equal(build.productId, 'cook')
  assert.match(build.commitSha, /^[0-9a-f]{40}$/)
  assert.equal(build.dirty, false, 'clean checkout stays clean in the build context')
  if (process.env.COOK_EXPECTED_COMMIT)
    assert.equal(build.commitSha, process.env.COOK_EXPECTED_COMMIT)
  console.log(`Passed HTTP checks: ${origin} (${build.commitSha})`)
}

try {
  docker('network', 'create', id)
  networkCreated = true
  docker('run', '-d', '--name', app, '--network', id, '--network-alias', 'cook', '-p', '127.0.0.1::80', image)
  docker('exec', app, 'nginx', '-t')
  docker('exec', app, 'sh', '-ec', 'test ! -e /app && test ! -e /usr/share/nginx/html/.git && test ! -e /usr/share/nginx/html/.env')
  await check(await ready(app))

  // A second real Nginx simulates a public gateway after HTTPS termination.
  const config = join(temporary, 'proxy.conf')
  writeFileSync(config, `server {
    listen 80 default_server;
    return 421;
}
server {
    listen 80;
    server_name cook.example.test;
    location / {
        proxy_pass http://cook:80;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
}
`)
  docker('create', '--name', proxy, '--network', id, '-p', '127.0.0.1::80', image)
  docker('cp', config, `${proxy}:/etc/nginx/conf.d/default.conf`)
  docker('start', proxy)
  docker('exec', proxy, 'nginx', '-t')
  await check(await ready(proxy))
}
finally {
  const removed = spawnSync('docker', ['rm', '-f', proxy, app], { stdio: 'ignore', timeout: 30_000 })
  if (networkCreated) {
    const network = spawnSync('docker', ['network', 'rm', id], { stdio: 'ignore', timeout: 30_000 })
    if (network.status !== 0 || removed.status !== 0) {
      console.error(`Check cleanup of Docker resources with prefix ${id}`)
      process.exitCode = 1
    }
  }
  rmSync(temporary, { recursive: true, force: true })
}
