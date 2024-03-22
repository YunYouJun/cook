import consola from 'consola'
import type OpenAI from 'openai'
import { baseChatCompletionCreateParams, baseModel, config, openai } from './config'

// TODO: pass params

export async function getCompletion(msg: string) {
  const chatCompletion = await openai.chat.completions.create({
    ...baseChatCompletionCreateParams,
    messages: [{ role: 'user', content: msg }],
    model: baseModel,
  })

  return chatCompletion.choices
}

/**
 * 获取 ai 生成的菜谱信息
 */
export async function getAIRecipeInfo(zhFoods: string[]) {
  /**
   * 限制输入长度
   */
  const promptFoods = zhFoods.join('、').slice(0, config.inputMaxLength)

  // 尽可能少的 token
  const tooltip = `
使用以下材料【${promptFoods}】做一道菜，请为这道菜起个名字，最好带有文化底蕴。
不要使用生僻字和标点符号。
并给出一个有趣的不超过100字的介绍。
格式类型：{
  "名称": "",
  "介绍": ""
}
直接给出可以被 JSON.parse 解析的字符串，不需要解释内容。`

  const messages: OpenAI.ChatCompletionMessageParam[] = [
    {
      role: 'system',
      content: tooltip,
    },
  ]

  const chatCompletion = await openai.chat.completions.create({
    ...baseChatCompletionCreateParams,
    messages,
    model: baseModel,
    // stream: true
  })

  consola.debug(chatCompletion)
  return chatCompletion.choices[0].message
}
