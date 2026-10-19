import { cpSync, existsSync, rmSync } from 'node:fs'
import { resolve } from 'node:path'

// Deploy targets (Netlify, Vercel, Cloudflare Pages, EdgeOne, Docker, Capacitor) read `dist/`
// at the repo root, so mirror the web app's static output there.
const root = resolve(import.meta.dirname, '..')
const webDist = resolve(root, 'apps/web/dist')
const rootDist = resolve(root, 'dist')

if (!existsSync(webDist))
  throw new Error('apps/web/dist not found, run `pnpm -C apps/web run generate` first')

rmSync(rootDist, { recursive: true, force: true })
cpSync(webDist, rootDist, { recursive: true, dereference: true })

console.log('Copied apps/web/dist to dist/')
