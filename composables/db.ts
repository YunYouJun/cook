import { useStorage } from '@vueuse/core'
import { lastDbUpdated, namespace } from '~/constants'

export function useIndexedDB() {
  const dbUpdated = useStorage(`${namespace}:lastDbUpdated`, lastDbUpdated)

  return {
    // db,
    // initDb,
    init: async () => {
      const count = await db.recipes.count()
      if (!count || dbUpdated.value !== lastDbUpdated) {
        dbUpdated.value = lastDbUpdated
        initDb()
      }
    },
  }
}
