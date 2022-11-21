import type { Ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useGtm } from '@gtm-support/vue-gtm'
import type { Recipe } from '~/types'

import { useRecipeStore } from '~/store/recipe'
import type { StuffItem } from '~/data/food'

export function useRecipe(recipe: Ref<Recipe>) {
  const gtm = useGtm()

  const rStore = useRecipeStore()
  const { curMode, curTool } = storeToRefs(rStore)
  const curStuff = computed(() => rStore.selectedStuff)

  // 默认严格模式
  const displayedRecipe = computed(() => {
    // if keyword exist, return result directly
    const keyword = rStore.keyword
    if (keyword)
      return recipe.value.filter(item => item.name.includes(keyword))

    if (curMode.value === 'strict') {
      return recipe.value.filter((item) => {
        const stuffFlag = curStuff.value.every(stuff => item.stuff.includes(stuff))
        const toolFlag = item.tools?.includes(curTool.value)
        return curTool.value ? stuffFlag && toolFlag : stuffFlag
      })
    }
    else if (curMode.value === 'loose') {
      return recipe.value.filter((item) => {
        const stuffFlag = curStuff.value.some(stuff => item.stuff.includes(stuff))
        const toolFlag = item.tools?.includes(curTool.value)

        // 同时存在 厨具和材料，则同时判断
        if (curTool.value && curStuff.value.length) {
          return stuffFlag && toolFlag
        }
        else {
          if (curStuff.value.length)
            return stuffFlag
          else if (curTool.value)
            return toolFlag

          return false
        }
      })
    }
    // survival
    else {
      return recipe.value.filter((item) => {
        const stuffFlag = item.stuff.every(stuff => curStuff.value.includes(stuff))
        const toolFlag = item.tools?.includes(curTool.value)
        return curTool.value ? stuffFlag && toolFlag : stuffFlag
      })
    }
  })

  /**
   * toggle tool
   * @param item
   */
  const clickTool = (item: StuffItem) => {
    const value = item.name
    rStore.toggleTools(value)

    gtm?.trackEvent({
      event: 'click',
      category: `tool_${value}`,
      action: 'click_tool',
      label: '工具',
    })
    gtm?.trackEvent({
      event: 'click_tool',
      action: item.name,
    })
  }

  return {
    displayedRecipe,
    clickTool,
  }
}
