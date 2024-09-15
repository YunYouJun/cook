import { meat, staple, vegetable } from '~/data/food'

export * from './cookbook'

const foodItems = [...vegetable, ...meat, ...staple]
const foodEmojiMap = new Map()
foodItems.forEach((item) => {
  foodEmojiMap.set(item.name, item.emoji)
})

/**
 * get emojis from stuff name array
 * @param stuff
 */
export function getEmojisFromStuff(stuff: string[]) {
  const emojis: string[] = stuff.map(name => foodEmojiMap.get(name)).filter(item => !!item)
  return emojis
}
