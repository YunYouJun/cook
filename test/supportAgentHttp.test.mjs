import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { createHash, generateKeyPairSync, verify } from 'node:crypto'
// eslint-disable-next-line test/no-import-node-test -- Standalone automation has no Nuxt or browser dependency.
import { test } from 'node:test'
import { createCookSupportClient } from '../scripts/lib/support-agent-http.mjs'

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
const config = { clientId: 'cook-agent-test', keyId: 'key-test', userId: 'maintainer-test', privateKey: privateKey.export({ type: 'pkcs8', format: 'pem' }) }

test('signed transport binds identity, body, exact route, permission and stable retry key', async () => {
  const calls = []
  const client = createCookSupportClient(config, { now: () => 1_700_000_000_000, fetch: async (url, options) => {
    calls.push({ url, options })
    return Response.json({ ok: true })
  } })
  const input = { id: 'ticket-test', version: 2, command: { action: 'status', status: 'in_progress' } }
  await client.command(input, 'fixed-operation-key')
  await client.command(input, 'fixed-operation-key')
  const claims = calls.map(({ url, options }) => {
    assert.equal(url, 'https://support.yunle.fun/api/integrations/v1/tickets/commands')
    assert.equal(options.redirect, 'error')
    const [header, payload, signature] = options.headers.authorization.slice(7).split('.')
    assert(verify('sha256', Buffer.from(`${header}.${payload}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(signature, 'base64url')))
    assert.deepEqual(JSON.parse(Buffer.from(header, 'base64url')), { alg: 'ES256', typ: 'support-integration+jwt', kid: config.keyId })
    const value = JSON.parse(Buffer.from(payload, 'base64url'))
    assert.equal(value.iss, config.clientId)
    assert.equal(value.sub, config.userId)
    assert.equal(value.aud, 'support-private-api')
    assert.equal(value.exp - value.iat, 60)
    assert.equal(value.scope, 'tickets:write')
    assert.equal(value.path, new URL(url).pathname)
    assert.equal(value.idempotencyKey, 'fixed-operation-key')
    assert.equal(value.bodyHash, createHash('sha256').update(options.body).digest('hex'))
    return value
  })
  assert.notEqual(claims[0].jti, claims[1].jti)
})

test('read and reply calls use their separate existing permissions', async () => {
  const scopes = []
  const client = createCookSupportClient(config, { fetch: async (_url, options) => {
    scopes.push(JSON.parse(Buffer.from(options.headers.authorization.split('.')[1], 'base64url')).scope)
    return Response.json({})
  } })
  await client.query({ productId: 'cook' })
  await client.command({ command: { action: 'reply.send' } }, 'reply-operation-key')
  assert.deepEqual(scopes, ['tickets:read', 'tickets:reply'])
})

test('configuration, network and response failures redact secrets and private payloads', async () => {
  assert.throws(() => createCookSupportClient({ ...config, baseUrl: 'https://attacker.example' }), /Support request failed/)
  assert.throws(() => createCookSupportClient({ ...config, privateKey: 'private-secret' }), /Support request failed/)
  const client = createCookSupportClient(config, { fetch: async () => {
    throw new Error('private-secret')
  } })
  await assert.rejects(client.command({ command: { action: 'note', content: 'private-ticket-content' } }, 'operation-key'), error => error.code === 'SUPPORT_REQUEST_FAILED' && !JSON.stringify(error).includes('private-secret'))
  const rejected = createCookSupportClient(config, { fetch: async () => Response.json({ code: 'STATE_CONFLICT', content: 'private-ticket-content' }, { status: 409 }) })
  await assert.rejects(rejected.command({ command: { action: 'note' } }, 'operation-key'), error => error.code === 'STATE_CONFLICT' && !String(error).includes('private-ticket-content'))
})
