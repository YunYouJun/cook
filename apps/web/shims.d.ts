declare interface Window {
  // extend the window
  wx: any
  /**
   * pwa install prompt event
   */
  deferredPrompt: Event | any
}

// with vite-plugin-vue-markdown, markdowns can be treat as Vue components
declare module '*.md' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<object, object, any>
  export default component
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<object, object, any>
  export default component
}
