import UnoCSS from 'unocss/vite'
import { defineConfig } from 'vite'
import vueDevTools from 'vite-plugin-vue-devtools'
import { groupIconVitePlugin } from 'vitepress-plugin-group-icons'

export default defineConfig({
  plugins: [
    UnoCSS(),
    groupIconVitePlugin({
      customIcon: {
        nodejs: 'vscode-icons:file-type-node',
        cli: 'vscode-icons:folder-type-cli',
        postcss: 'vscode-icons:file-type-postcss',
        playwright: 'vscode-icons:file-type-playwright',
        vitepress: 'simple-icons:vitepress',
        typedoc: 'vscode-icons:file-type-typedoc',
        类型: 'vscode-icons:file-type-typescript',
        eslint: 'vscode-icons:file-type-eslint',
      },
    }),
    vueDevTools(),
  ],
})
