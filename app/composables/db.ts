import { useStorage } from '@vueuse/core'
import { lastDbUpdated, namespace } from '~/constants'
import { db, initDb } from '~/utils/db'

let initialization: Promise<void> | undefined

export function useIndexedDB() {
  const dbUpdated = useStorage(`${namespace}:lastDbUpdated`, lastDbUpdated)

  return {
    init: () => {
      initialization ??= (async () => {
        const count = await db.recipes.count()
        if (!count || dbUpdated.value !== lastDbUpdated) {
          await initDb()
          dbUpdated.value = lastDbUpdated
        }
      })().finally(() => { initialization = undefined })
      return initialization
    },
  }
}
