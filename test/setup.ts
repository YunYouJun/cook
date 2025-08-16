import { vi } from 'vitest'
import 'fake-indexeddb/auto'

// Mock useScriptGoogleTagManager globally
vi.stubGlobal('useScriptGoogleTagManager', () => ({
  proxy: {
    dataLayer: {
      push: vi.fn(),
    },
  },
}))

// Mock onMounted to prevent Vue warnings in tests
vi.mock('vue', async () => {
  const actual = await vi.importActual('vue')
  return {
    ...actual,
    onMounted: vi.fn(),
  }
})
