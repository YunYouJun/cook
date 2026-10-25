import assert from 'node:assert/strict'
// eslint-disable-next-line test/no-import-node-test -- The redirect Worker runs independently of Nuxt and Vitest.
import { test as it } from 'node:test'
import worker from '../workers/docs-redirect/index.mjs'

for (const [source, expected] of [
  ['https://docs.cook.yunle.fun/', 'https://cook.yunle.fun/'],
  ['https://docs.cook.yunle.fun/privacy', 'https://cook.yunle.fun/privacy'],
  ['http://docs.cook.yunle.fun/guide/ios-beta?from=old&lang=zh', 'https://cook.yunle.fun/guide/ios-beta?from=old&lang=zh'],
  ['https://docs.cook.yunle.fun/privacy.html', 'https://cook.yunle.fun/privacy.html'],
  ['https://docs.cook.yunle.fun/guide/%E4%B8%AD%E6%96%87/?q=a%2Fb&q=a+b', 'https://cook.yunle.fun/guide/%E4%B8%AD%E6%96%87/?q=a%2Fb&q=a+b'],
  ['https://docs.cook.yunle.fun//example.com/privacy?next=https://example.com', 'https://cook.yunle.fun//example.com/privacy?next=https://example.com'],
]) {
  it(`permanently redirects ${source}`, () => {
    const response = worker.fetch(new Request(source))
    assert.equal(response.status, 301)
    assert.equal(response.headers.get('location'), expected)
    assert.equal(new URL(response.headers.get('location')).origin, 'https://cook.yunle.fun')
    assert.equal(response.body, null)
  })
}

it('supports HEAD requests without fetching an origin', () => {
  const response = worker.fetch(new Request('https://docs.cook.yunle.fun/privacy', { method: 'HEAD' }))
  assert.equal(response.status, 301)
  assert.equal(response.headers.get('location'), 'https://cook.yunle.fun/privacy')
  assert.equal(response.body, null)
})
