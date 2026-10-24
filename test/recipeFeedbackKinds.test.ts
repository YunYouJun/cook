import { describe, expect, it } from 'vitest'
import { createFeedbackRemote } from '../apps/web/app/adapters/feedback'
import { recipeFeedbackKinds } from '../apps/web/app/utils/feedback'

describe('recipe feedback sources', () => {
  it('offers only relevant source issues, including both when both sources exist', () => {
    const kinds = (recipe: { bv?: string, link?: string }) => recipeFeedbackKinds(recipe).map(item => item.kind)
    expect(kinds({ link: 'https://example.com/recipe' })).toEqual(['text', 'stuff', 'info', 'other'])
    expect(kinds({ bv: 'BV1NE411Q7Jj' })).toEqual(['video', 'stuff', 'info', 'other'])
    expect(kinds({ bv: 'BV1NE411Q7Jj', link: 'https://example.com/recipe' })).toEqual(['video', 'text', 'stuff', 'info', 'other'])
    expect(kinds({})).toEqual(['stuff', 'info', 'other'])
  })

  it('sends the selected issue and source snapshot through the existing report transport', async () => {
    const calls: unknown[] = []
    const remote = createFeedbackRemote(async (_name, body) => {
      calls.push(body)
      return { ok: true }
    }, 'device-0123456789abcdef')
    await remote.report({ recipeId: 708, recipeName: '土豆炖排骨', kind: 'text', sourceBv: '', sourceLink: 'https://example.com/recipe' })
    expect(calls).toEqual([{ action: 'report', platform: 'web', deviceId: 'device-0123456789abcdef', recipeId: 708, recipeName: '土豆炖排骨', kind: 'text', sourceBv: '', sourceLink: 'https://example.com/recipe' }])
  })
})
