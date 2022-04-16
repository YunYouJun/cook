import { meat, staple, vegetable } from '~/data/food'

/**
 * 从材料生成 Emoji
 * @param stuff
 * @returns
 */
export function generateEmojisFromStuff(stuff: string[]) {
  const emojis: string[] = []
  stuff.forEach((item) => {
    const kinds = [vegetable, meat, staple]
    kinds.forEach((kind) => {
      kind.forEach((m) => {
        if (m.name === item)
          emojis.push(m.emoji)
      })
    })
  })
  return emojis
}
