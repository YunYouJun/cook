import { defineConfig } from 'bumpp'

export default defineConfig({
  release: 'prompt',
  commit: false,
  tag: false,
  push: false,
  files: [
    'package.json',
  ],
})
