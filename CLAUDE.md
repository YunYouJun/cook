# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Cook (食用手册) is a Chinese recipe web/mobile app for finding recipes based on available ingredients. Built with Nuxt 4 (Vue 3, CSR-only), Ionic UI, and Capacitor for native iOS/Android apps. The UI and content are in Chinese.

## Common Commands

```bash
pnpm install              # Install deps (auto-runs nuxt prepare via postinstall)
pnpm convert              # Convert CSV recipe data → JSON (required before first run)
pnpm dev                  # Dev server on http://localhost:3333
pnpm lint                 # ESLint check (uses @antfu/eslint-config flat config)
pnpm lint --fix           # Auto-fix lint issues
pnpm typecheck            # TypeScript type checking via vue-tsc
pnpm test                 # Run Vitest tests
pnpm test -- --run        # Run tests once without watch
pnpm build                # Full production build (convert + nuxt generate)
```

### Mobile Development

```bash
pnpm dev:ios              # iOS with live reload (cap run ios -l)
pnpm dev:android          # Android dev
pnpm ios                  # Open in Xcode
pnpm android              # Open in Android Studio
```

## Architecture

### Data Pipeline

CSV source files (`app/data/recipe.csv`, `app/data/incompatible-foods.csv`) → `pnpm convert` (via `packages/cook` CLI) → JSON files (`app/data/recipe.json`, `app/data/incompatible-foods.json`) → consumed at runtime. The `pnpm fetch` command pulls fresh data from Feishu (飞书) API.

### Monorepo Structure (pnpm workspaces)

- **Root** — Nuxt app (main application)
- **packages/cook** — CLI tool with `convert` and `fetch` commands
- **docs** — VitePress documentation site
- **scripts** — Build/utility scripts

### App Source (`app/`)

- **pages/** — Nuxt file-based routing; main interface uses Ionic tabs (`tabs.vue`)
- **components/** — Organized by feature: `recipe/`, `tags/`, `layouts/`, `common/`
- **composables/store/** — Pinia stores: `app`, `recipe`, `favorite`, `history`, `user`
- **composables/** — Shared logic: `db.ts` (IndexedDB via Dexie), `dark.ts`, `recipe.ts`, `incompatible-foods.ts`
- **data/** — Static JSON data + `food.ts` (food/tool definitions)
- **types/** — TypeScript type definitions
- **styles/** — SCSS including CSS variables (`css-vars.scss`)

### Key Patterns

- **CSR-only**: SSR is disabled in `nuxt.config.ts`; app is statically generated
- **Client storage**: Dexie (IndexedDB) for favorites, history, user preferences — enables offline support
- **Styling**: UnoCSS with Wind preset (Tailwind-compatible), attributify mode, and custom shortcuts (`tag`, `btn`)
- **Dark mode**: Class-based via `@nuxtjs/color-mode` (classSuffix: '')
- **Icons**: Iconify with multiple packs (carbon, mdi, twemoji); food tool icons dynamically safelisted in `uno.config.ts`

### Prerendered Routes

`/`, `/random`, `/help`, `/user`, `/404`, `/settings` are prerendered during static generation.

## Testing

Tests live in `test/` directory. Vitest uses jsdom environment with setup in `test/setup.ts`. Config in `vitest.config.ts`. Run a single test file:

```bash
pnpm test -- test/recipe.test.ts
```

## Code Style

Uses `@antfu/eslint-config` (flat config) with UnoCSS and formatters enabled. Pre-commit hook runs `lint-staged` via `simple-git-hooks`. Ignored paths include `app/data/*.json`, `ios/`, `android/`, `dist/`.

## Environment Variables

See `.env.example`:

- `FEISHU_APP_ID` / `FEISHU_APP_SECRET` — For fetching recipe data from Feishu
- `APPLE_DEVELOPMENT_TEAM` — iOS signing

## Node/Package Manager

- pnpm: 10.32.1 (enforced via `packageManager` field)
