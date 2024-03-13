// http://localhost:3001/api/recipes/image/generate

import process from 'node:process'
import { meat, staple, vegetable } from '~/data/food'

// internal temp
// const sdBaseUrl = 'http://30.30.168.63:7860/'
const sdBaseUrl = process.env.SD_API_BASE_URL

const payload = {
  // denoising_strength: 0,
  prompt: 'food', // 提示词
  negative_prompt: '', // 反向提示词
  seed: -1, // 种子，随机数
  batch_size: 2, // 每次张数
  n_iter: 1, // 生成批次
  steps: 50, // 生成步数
  cfg_scale: 7, // 关键词相关性
  width: 512, // 宽度
  height: 512, // 高度
  restore_faces: false, // 脸部修复
  tiling: false, // 可平埔
  // override_settings: {
  //   sd_model_checkpoint: 'wlop-any.ckpt [7331f3bc87]',
  // }, // 一般用于修改本次的生成图片的stable diffusion 模型，用法需保持一致
  // script_args: [
  //   0,
  //   true,
  //   true,
  //   'LoRA',
  //   'dingzhenlora_v1(fa7c1732cc95)',
  //   1,
  //   1,
  // ], // 一般用于lora模型或其他插件参数，如示例，我放入了一个lora模型， 1，1为两个权重值，一般只用到前面的权重值1
  sampler_index: 'Euler', // 采样方法
}

// with api /sdapi/v1/txt2img

export interface Txt2ImgResponse {
  images: string[]
}

const stuffItems = [
  ...vegetable,
  ...meat,
  ...staple,
]

export default defineEventHandler(async (e) => {
  const body = await readBody(e)

  const zhFoods = body.foods as string[]
  const enFoods = zhFoods.map((food) => {
    const item = stuffItems.find(item => item.name === food)
    if (item)
      return item.en
    return ''
  })

  // TODO: 过滤 prompt 只能是食材
  // <lora:TODO:1>,
  payload.prompt = `food focus,transparent background,${enFoods.join(',')}`

  console.log(payload.prompt)

  const data = await $fetch<Txt2ImgResponse>('/sdapi/v1/txt2img', {
    baseURL: sdBaseUrl,
    body: payload,
    method: 'POST',
  })
  console.log(data)
  return data
})
