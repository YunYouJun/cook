import { defineConfig } from 'bumpp'

export default defineConfig({
  release: 'prompt',
  all: true,
  commit: false,
  tag: false,
  push: false,
  files: [
    'package.json',
  ],
})
