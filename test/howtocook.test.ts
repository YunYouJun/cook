import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { describe, expect, it } from 'vitest'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { howToCookPath, parseHowToCook } from '../scripts/lib/howtocook'

const dataDir = resolve(process.cwd(), 'packages/data')

describe('parseHowToCook', () => {
  it('keeps sections, lists and notes, and drops the title, images and closing line', () => {
    const text = parseHowToCook(`# 番茄炒蛋的做法

番茄炒蛋是一道家常菜。见[教程](../tips/x.md)或[视频](https://www.bilibili.com/video/BV1xx)。

## 必备原料和工具

* 番茄
* 鸡蛋

## 操作

1. 鸡蛋打散
   * 加一点盐
2. 番茄切块
3. ![成品](./番茄炒蛋.jpg)

   出锅

\`\`\`mermaid
graph TD
\`\`\`

## 附加内容

如果您遵循本指南的制作流程而发现有问题或可以改进的流程，请提出 Issue 或 Pull request 。
`)
    expect(text).toEqual({
      blocks: [
        { type: 'paragraph', text: '番茄炒蛋是一道家常菜。见教程或[视频](https://www.bilibili.com/video/BV1xx)。' },
        { type: 'heading', text: '必备原料和工具' },
        { type: 'list', ordered: false, items: ['番茄', '鸡蛋'] },
        { type: 'heading', text: '操作' },
        { type: 'list', ordered: true, items: ['鸡蛋打散\n• 加一点盐', '番茄切块\n出锅'] },
      ],
      images: 1,
    })
  })

  it('maps HowToCook links to files in the repository', () => {
    expect(howToCookPath('https://github.com/Anduin2017/HowToCook/blob/master/dishes/meat_dish/%E5%8F%AF%E4%B9%90%E9%B8%A1%E7%BF%85.md'))
      .toBe('dishes/meat_dish/可乐鸡翅.md')
    expect(howToCookPath('https://example.com/recipe')).toBeUndefined()
  })
})

// 加了 HowToCook 的菜要运行 pnpm howtocook，App 才有离线正文
describe('recipe-text.json', () => {
  it('has the text of every recipe linked to HowToCook, and nothing else', () => {
    const recipes = parseRecipeCsv(readFileSync(resolve(dataDir, 'recipe.csv'), 'utf8'))
    const texts = JSON.parse(readFileSync(resolve(dataDir, 'recipe-text.json'), 'utf8'))
    const linked = recipes.filter(recipe => howToCookPath(recipe.link)).map(recipe => String(recipe.id))
    expect(Object.keys(texts.recipes).sort()).toEqual(linked.sort())
  })
})
