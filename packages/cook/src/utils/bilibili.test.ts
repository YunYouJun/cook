import { describe, expect, it } from 'vitest'
import { bilibiliViewApi, classifyBilibiliCode } from './bilibili.js'

describe('classifyBilibiliCode', () => {
  it('treats removed or hidden videos as dead', () => {
    expect(classifyBilibiliCode(0)).toBe('ok')
    expect(classifyBilibiliCode(-404)).toBe('dead')
    expect(classifyBilibiliCode(62002)).toBe('dead')
    expect(classifyBilibiliCode(-412)).toBe('unknown')
  })
})

describe('bilibiliViewApi', () => {
  it('supports BV and av ids', () => {
    expect(bilibiliViewApi('BV1NE411Q7Jj')).toContain('bvid=BV1NE411Q7Jj')
    expect(bilibiliViewApi('av895289430')).toContain('aid=895289430')
  })
})
