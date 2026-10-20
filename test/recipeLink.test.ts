import { describe, expect, it, vi } from 'vitest'
import { setCookEventTracker } from '../apps/web/app/utils/analytics'
import { getRecipeFeedbackLink, getRecipeLink, getRecipeTextLink, openRecipe } from '../apps/web/app/utils/recipe'

describe('getRecipeLink', () => {
  it('records the video click before leaving, and analytics failures never block navigation', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    const send = vi.fn(() => {
      throw new Error('blocked')
    })
    setCookEventTracker(send)
    openRecipe({ bv: 'BV1xx' })
    expect(send).toHaveBeenCalledExactlyOnceWith('click_recipe_video')
    expect(open).toHaveBeenCalledExactlyOnceWith('https://www.bilibili.com/video/BV1xx', '_blank')
    setCookEventTracker(() => {})
    open.mockRestore()
  })

  it('does nothing for a recipe without a video or link', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    const send = vi.fn()
    setCookEventTracker(send)
    openRecipe({})
    expect(send).not.toHaveBeenCalled()
    expect(open).not.toHaveBeenCalled()
    setCookEventTracker(() => {})
    open.mockRestore()
  })

  it('prefers the bilibili video and keeps the text recipe as a second entry', () => {
    const recipe = { link: 'https://example.com/recipe', bv: 'BV1xx' }
    expect(getRecipeLink(recipe)).toBe('https://www.bilibili.com/video/BV1xx')
    expect(getRecipeTextLink(recipe)).toBe('https://example.com/recipe')
  })

  it('uses the recorded link when there is no video', () => {
    expect(getRecipeLink({ link: 'https://example.com/recipe' })).toBe('https://example.com/recipe')
    expect(getRecipeTextLink({ link: 'https://example.com/recipe' })).toBe('')
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
