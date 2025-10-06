import { defineConfig } from 'bumpp'

export default defineConfig({
  release: 'prompt',
  all: true,
  commit: true,
  tag: true,
  push: true,
  files: [
    'package.json',
  ],
})
