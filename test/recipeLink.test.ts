import { describe, expect, it } from 'vitest'
import { getRecipeFeedbackLink, getRecipeLink } from '../app/utils/recipe'

describe('getRecipeLink', () => {
  it('prefers the recorded link', () => {
    expect(getRecipeLink({ link: 'https://example.com/recipe', bv: 'BV1xx' })).toBe('https://example.com/recipe')
  })

  it('falls back to the bilibili video', () => {
    expect(getRecipeLink({ link: '', bv: 'BV1xx' })).toBe('https://www.bilibili.com/video/BV1xx')
  })
})

describe('getRecipeFeedbackLink', () => {
  it('prefills the recipe issue form', () => {
    const url = new URL(getRecipeFeedbackLink({ name: '番茄炒蛋', bv: 'BV1xx' }))
    expect(url.searchParams.get('template')).toBe('recipe.yml')
    expect(url.searchParams.get('name')).toBe('番茄炒蛋')
    expect(url.searchParams.get('video')).toBe('https://www.bilibili.com/video/BV1xx')
  })
})
