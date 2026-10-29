import { Buffer } from 'node:buffer'
import { createHash, createPrivateKey, randomUUID, sign } from 'node:crypto'

const origin = 'https://support.yunle.fun'

function failure(code = 'SUPPORT_REQUEST_FAILED') {
  return Object.assign(new Error('Support request failed; retain the saved operation for retry'), { code })
}

/**
 * Create the minimal signed HTTP transport for the existing scoped Support agent API.
 * Request bodies and credentials are never included in thrown errors.
 * @param {{clientId:string,keyId:string,userId:string,privateKey:string,baseUrl?:string}} config Cook-only agent credential.
 * @param {{fetch?:typeof fetch,now?:()=>number,nonce?:()=>string}} options HTTP and clock dependencies.
 * @returns {{query:Function,command:Function}} Versioned Support operations.
 */
export function createCookSupportClient(config, { fetch: send = globalThis.fetch, now = Date.now, nonce = randomUUID } = {}) {
  if (!config || [config.clientId, config.keyId, config.userId].some(value => typeof value !== 'string' || !/^[\w-]{1,128}$/.test(value)) || (config.baseUrl && config.baseUrl !== origin))
    throw failure('INVALID_AGENT_CONFIG')
  let key
  try {
    key = createPrivateKey(config.privateKey)
  }
  catch {
    throw failure('INVALID_AGENT_CONFIG')
  }
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1')
    throw failure('INVALID_AGENT_CONFIG')

  async function request(path, scope, input, operationKey) {
    if (!/^[\w-]{8,128}$/.test(operationKey))
      throw failure('INVALID_OPERATION_KEY')
    const body = JSON.stringify(input)
    const issuedAt = Math.floor(now() / 1000)
    const header = { alg: 'ES256', typ: 'support-integration+jwt', kid: config.keyId }
    const claims = { iss: config.clientId, sub: config.userId, aud: 'support-private-api', iat: issuedAt, exp: issuedAt + 60, jti: nonce(), scope, path, method: 'POST', idempotencyKey: operationKey, bodyHash: createHash('sha256').update(body).digest('hex') }
    const content = [header, claims].map(value => Buffer.from(JSON.stringify(value)).toString('base64url')).join('.')
    const signature = sign('sha256', Buffer.from(content), { key, dsaEncoding: 'ieee-p1363' }).toString('base64url')
    let response
    try {
      response = await send(origin + path, { method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15_000), headers: { 'content-type': 'application/json', 'authorization': `Bearer ${content}.${signature}`, 'idempotency-key': operationKey }, body })
    }
    catch {
      throw failure()
    }
    let result
    try {
      result = await response.json()
    }
    catch {
      throw failure()
    }
    if (!response.ok) {
      const code = typeof result?.code === 'string' && /^[A-Z_]{1,64}$/.test(result.code) ? result.code : 'SUPPORT_REQUEST_FAILED'
      throw failure(code)
    }
    return result
  }

  return {
    query: input => request('/api/integrations/v1/tickets/query', 'tickets:read', input, nonce()),
    command: (input, operationKey) => request('/api/integrations/v1/tickets/commands', input.command?.action === 'reply.send' ? 'tickets:reply' : 'tickets:write', input, operationKey),
  }
}
