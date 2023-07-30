import { useStorage } from '@vueuse/core'
import { lastDbUpdated, namespace } from '~/constants'

export function useIndexedDB() {
  const dbUpdated = useStorage(`${namespace}:lastDbUpdated`, lastDbUpdated)

  return {
    init: async () => {
      const count = await db.recipes.count()
      if (!count || dbUpdated.value !== lastDbUpdated) {
        await initDb()
        dbUpdated.value = lastDbUpdated
      }
    },
  }
}
