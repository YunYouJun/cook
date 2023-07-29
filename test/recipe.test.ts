import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useRecipeStore } from '../composables/store'

describe('recipe interaction', () => {
  beforeEach(() => {
    // creates a fresh pinia and make it active so it's automatically picked
    // up by any useStore() call without having to pass it to it:
    // `useStore(pinia)`
    setActivePinia(createPinia())
  })

  it('toggle stuff', () => {
    const rStore = useRecipeStore()
    rStore.toggleStuff('土豆')
    expect(rStore.selectedStuff.includes('土豆')).toBe(true)
    rStore.toggleStuff('土豆')
    expect(rStore.selectedStuff.includes('土豆')).toBe(false)
  })

  it('toggle tool', () => {
    const rStore = useRecipeStore()
    rStore.toggleTools('电饭煲')
    expect(rStore.curTool === '电饭煲').toBe(true)
    rStore.toggleTools('微波炉')
    expect(rStore.curTool === '微波炉').toBe(true)
  })
})

describe('recipe mode', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('loose mode', () => {
    const rStore = useRecipeStore()

    rStore.reset()
    rStore.addStuff('土豆')
    rStore.addStuff('腊肠')

    rStore.curTool = '电饭煲'

    expect(rStore.selectedStuff).toStrictEqual(['土豆', '腊肠'])
    rStore.setMode('strict')

    rStore.displayedRecipe.forEach((item) => {
      expect(item.stuff.includes('土豆') || item.stuff.includes('腊肠')).toBe(true)
      expect(item.tools?.includes('电饭煲')).toBe(true)
    })
  })

  it('strict mode', () => {
    const rStore = useRecipeStore()

    rStore.reset()
    rStore.addStuff('土豆')
    rStore.addStuff('腊肠')

    rStore.curTool = '电饭煲'

    expect(rStore.selectedStuff).toStrictEqual(['土豆', '腊肠'])
    rStore.setMode('strict')

    rStore.displayedRecipe.forEach((item) => {
      expect(item.stuff.includes('土豆') && item.stuff.includes('腊肠')).toBe(true)
      expect(item.tools?.includes('电饭煲')).toBe(true)
    })
  })

  it('survival mode', () => {
    const rStore = useRecipeStore()

    rStore.reset()
    rStore.addStuff('土豆')
    rStore.addStuff('腊肠')

    expect(rStore.selectedStuff).toStrictEqual(['土豆', '腊肠'])
    rStore.setMode('survival')

    rStore.displayedRecipe.forEach((item) => {
      const filtered = item.stuff.every(stuff => rStore.selectedStuff.includes(stuff))
      expect(filtered).toBe(true)
    })
  })
})
