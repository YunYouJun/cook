import consola from 'consola'
import type { AIRecipeInfo } from '~/packages/ai/src'
import { getAIRecipeInfo } from '~/packages/ai/src'

export default defineEventHandler(async (event) => {
  const body = await readBody(event)
  const zhFoods = body.foods as string[]

  consola.debug(zhFoods)

  const data = await getAIRecipeInfo(zhFoods)
  const { content } = data

  let unWrapperContent = content || ''
  const startPos = unWrapperContent.indexOf('{')
  const endPos = unWrapperContent.lastIndexOf('}')

  if (startPos === -1 || endPos === -1) {
    // eslint-disable-next-line no-console
    console.log(content)
    return
  }

  unWrapperContent = unWrapperContent.slice(startPos, endPos + 1)
  unWrapperContent = (unWrapperContent || '{}')?.replace('```json\n', '').replace('```', '')

  unWrapperContent = unWrapperContent.endsWith('}') ? unWrapperContent : `${unWrapperContent}}`

  let coupletData: AIRecipeInfo | undefined
  try {
    coupletData = JSON.parse(unWrapperContent) as AIRecipeInfo
  }
  catch (e) {
    // eslint-disable-next-line no-console
    console.log(content)
    console.error(e)
  }

  return coupletData
})
