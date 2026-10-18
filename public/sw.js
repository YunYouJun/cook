// Retire the old Workbox worker now that Cook no longer enables PWA caching.
// Keep this URL available so returning visitors can leave the cached 2025 app.
self.addEventListener('install', (event) => {
  event.waitUntil(self.skipWaiting())
})

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    await self.clients.claim()
    const windows = await self.clients.matchAll({ type: 'window' })
    await self.registration.unregister()
    await Promise.allSettled(windows.map(window => window.navigate(window.url)))
  })())
})

// No fetch handler: all requests use the network. Preserve saved user data.
