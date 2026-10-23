/** One server adapter for both the Web SDK and the existing iOS HTTP protocol. */
const { createHash } = require('node:crypto')
const process = require('node:process')

const CATEGORY = { bug: 'problem', idea: 'suggestion', recipe: 'suggestion', other: 'other' }
const LABEL = { bug: '使用问题', idea: '功能建议', recipe: '菜谱需求', other: '其他反馈' }
let loadClient = () => import('@yunlefun/support-client/server')

/** Private text stays in Support; Cook keeps only recipe data and search signals. */
async function submitAppFeedback({ category, note, contact, source, reporter, day, submissionId }) {
  const privateKey = process.env.COOK_SUPPORT_PRIVATE_KEY
  const keyId = process.env.COOK_SUPPORT_KEY_ID
  if (!privateKey || !keyId)
    return { ok: false, code: 'SUPPORT_UNAVAILABLE' }

  const description = [`【${LABEL[category]}】`, note, ...(contact ? [`联系方式：${contact}`] : [])].join('\n\n')
  const input = {
    productId: 'cook',
    category: CATEGORY[category],
    description,
    context: { source: `cook-${source.platform}`, ...(source.version ? { version: source.version } : {}) },
  }
  // Legacy apps do not send submissionId. Include all fields, not just note, in their daily key.
  const key = submissionId || `legacy_${createHash('sha256').update(JSON.stringify([reporter.key, day, input])).digest('hex')}`
  try {
    const { createSupportServerClient } = await loadClient()
    const client = createSupportServerClient({
      clientId: 'cook-server',
      keyId,
      privateKey: privateKey.replace(/\\n/g, '\n'),
      // Leave time for the existing iOS client's 10-second request deadline.
      fetch: (url, init) => fetch(url, { ...init, signal: AbortSignal.timeout(8000) }),
      ...(reporter.uid ? { userId: reporter.uid } : { guestId: reporter.key }),
    })
    const receipt = await client.submitFeedback(input, key)
    return { ok: true, receipt }
  }
  catch (error) {
    // Do not log the SDK request, description, contact, token, or private key.
    const code = error && error.code
    if (code === 'RATE_LIMITED')
      return { ok: false, code: 'RATE_LIMITED' }
    if (code === 'STATE_CONFLICT')
      return { ok: false, code: 'SUBMISSION_CONFLICT' }
    return { ok: false, code: 'SUPPORT_UNAVAILABLE' }
  }
}

exports.submitAppFeedback = submitAppFeedback
exports.setClientLoaderForTest = (loader) => {
  loadClient = loader
}
