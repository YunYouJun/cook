import type { PersonalCatalog } from '@yunyoujun/cook/personal-catalog'
import { emptyPersonalCatalog, exportPersonalCatalog, parsePersonalCatalog } from '@yunyoujun/cook/personal-catalog'

export function readPersonalCatalog(storage: Pick<Storage, 'getItem'>, key: string): PersonalCatalog {
  const raw = storage.getItem(key)
  return raw === null ? emptyPersonalCatalog() : parsePersonalCatalog(raw)
}

export function updatePersonalCatalog(storage: Pick<Storage, 'getItem' | 'setItem'>, key: string, update: (value: PersonalCatalog) => PersonalCatalog): PersonalCatalog {
  const next = update(readPersonalCatalog(storage, key))
  storage.setItem(key, exportPersonalCatalog(next))
  return next
}
