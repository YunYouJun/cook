import { File as NodeFile } from 'node:buffer'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LocalVideoDatabase } from '../apps/web/app/utils/localRecipeVideo'

const databases: LocalVideoDatabase[] = []
function database() {
  const db = new LocalVideoDatabase(`video-test-${Math.random()}`)
  databases.push(db)
  return db
}
afterEach(async () => {
  for (const db of databases.splice(0))
    await db.delete()
})
const file = (name: string, body = 'video') => new NodeFile([body], name, { type: 'video/mp4' }) as unknown as File

describe('local recipe videos', () => {
  it('keeps the old attachment on failed replacement; preserves export bytes after reopening', async () => {
    const db = database()
    await db.saveVideo('id', '菜谱', file('old.mp4'))
    vi.spyOn(db.videos, 'put').mockRejectedValueOnce(new Error('quota'))
    await expect(db.saveVideo('id', '菜谱', file('new.mp4'))).rejects.toThrow('quota')
    db.close()
    await db.open()
    const saved = await db.videos.get('id')
    expect(saved?.filename).toBe('old.mp4')
    expect(await saved?.blob.text()).toBe('video')
    expect((await db.videos.toArray()).map(item => item.recipeName)).toEqual(['菜谱'])
    await db.videos.delete('id')
    expect(await db.videos.get('id')).toBeUndefined()
  })
  it('rejects unsupported and empty files before replacing existing data', async () => {
    const db = database()
    await db.saveVideo('id', '菜谱', file('old.mp4'))
    await expect(db.saveVideo('id', '菜谱', file('script.html'))).rejects.toThrow()
    await expect(db.saveVideo('id', '菜谱', file('empty.mp4', ''))).rejects.toThrow()
    expect((await db.videos.get('id'))?.filename).toBe('old.mp4')
  })
})
