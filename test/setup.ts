import { vi } from 'vitest'
import { ref } from 'vue'
import 'fake-indexeddb/auto'

// Mock useScriptGoogleTagManager globally
vi.stubGlobal('useScriptGoogleTagManager', () => ({
  proxy: {
    dataLayer: {
      push: vi.fn(),
    },
  },
}))

// Mock @vueuse/core useStorage to use plain ref (no localStorage) in tests
vi.mock('@vueuse/core', async () => {
  const actual = await vi.importActual('@vueuse/core')
  return {
    ...actual,
    useStorage: (_key: string, defaultValue: unknown) => ref(
      typeof defaultValue === 'function' ? defaultValue() : defaultValue,
    ),
  }
})

// Mock onMounted to prevent Vue warnings in tests
vi.mock('vue', async () => {
  const actual = await vi.importActual('vue')
  return {
    ...actual,
    onMounted: vi.fn(),
  }
})
