import { describe, expect, it } from 'vitest'
import { getRecipeLink } from '../app/utils/recipe'

describe('getRecipeLink', () => {
  it('prefers the recorded link', () => {
    expect(getRecipeLink({ link: 'https://example.com/recipe', bv: 'BV1xx' })).toBe('https://example.com/recipe')
  })

  it('falls back to the bilibili video', () => {
    expect(getRecipeLink({ link: '', bv: 'BV1xx' })).toBe('https://www.bilibili.com/video/BV1xx')
  })
})
