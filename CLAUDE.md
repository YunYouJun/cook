# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Cook (食用手册) is a Chinese recipe web/mobile app for finding recipes based on available ingredients. Built with Nuxt 4 (Vue 3, CSR-only), Ionic UI, and Capacitor for native iOS/Android apps. The UI and content are in Chinese.

## Common Commands

```bash
pnpm install              # Install deps (auto-runs nuxt prepare via postinstall)
pnpm convert              # Convert CSV recipe data → JSON (required before first run)
pnpm validate             # Validate recipe.csv (ids, ingredient/tool dictionary, values, video ids)
pnpm feedback:pull        # Pull in-app feedback + search signals from CloudBase (needs .env)
pnpm eval:search          # Evaluate recommendation ranking against real search signals
pnpm howtocook <dir>      # Regenerate packages/data/recipe-text.json (in-app text recipes) from a HowToCook checkout
pnpm fixtures             # Regenerate shared Web/iOS fixtures: recommend, search, incompatible (after data, alias, ranking or search changes)
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

CSV source files (`packages/data/recipe.csv`, `packages/data/incompatible-foods.csv`) → `pnpm convert` (via `packages/cook` CLI) → JSON files (`packages/data/recipe.json`, `packages/data/incompatible-foods.json`) → consumed at runtime. The `pnpm fetch` command pulls fresh data from Feishu (飞书) API. Data rules, validation and the feedback loop are in `docs/dev/data.md`; every recipe has a permanent `id` column (favorites, feedback and search signals key on it): never change or reuse an id; new recipes take max id + 1.

Project decisions are recorded in `docs/decisions/` — read the relevant one before changing direction, and add a new record for significant decisions.

### Monorepo Structure (pnpm workspaces)

Layout follows `docs/decisions/0003-native-ios-monorepo.md`; the plan is in `docs/dev/ios-roadmap.md`.

- **apps/ios/CookCore** — Swift Package porting the recommend/search/incompatible logic for the native iOS app; `swift test --package-path apps/ios/CookCore` (after `pnpm convert`) runs the shared fixtures. Change TS and Swift logic together
- **apps/web** — Nuxt app (main application). Root scripts (`pnpm dev`, `pnpm build`, `pnpm typecheck`) proxy to it; `pnpm build` copies `apps/web/dist` to the root `dist/` that deploy targets and Capacitor read
- **packages/data** — Data shared by Web and iOS: recipe CSV sources and the JSON generated from them (imported in the web app as `@cook/data/*`), the food dictionary (`food.json`), pantry defaults (`pantry.json`), ingredient aliases (`aliases.json`), sentence-input word lists (`query.json`), text recipe bodies the iOS app shows offline (`recipe-text.json`, from `pnpm howtocook`), shared fixtures (`fixtures/*.json`, regenerate with `pnpm fixtures`; `fixtures/query.json` is hand-written), and JSON Schemas (`schema/`). `recipe-meta.json` carries `schemaVersion` (bump `DATA_SCHEMA_VERSION` in `packages/cook/src/utils/config.ts` and CookCore together on breaking shape changes) and `dataVersion` (content hash)
- **packages/cook** — Types, recommendation/search logic (the TS core the iOS CookCore mirrors), and the CLI (`convert`, `fetch`)
- **docs** — VitePress documentation site
- **scripts** — Build/utility scripts
- **Root** — Workspace tooling, tests (`test/`), Capacitor shell (`capacitor.config.ts`, `ios/`, `android/`), `cloudfunctions/`

### App Source (`apps/web/app/`)

- **pages/** — Nuxt file-based routing; main interface uses Ionic tabs (`tabs.vue`)
- **components/** — Organized by feature: `recipe/`, `tags/`, `layouts/`, `common/`
- **composables/store/** — Pinia stores: `app`, `recipe`, `favorite`, `history`, `user`
- **composables/** — Shared logic: `db.ts` (IndexedDB via Dexie), `dark.ts`, `recipe.ts`, `incompatible-foods.ts`
- **data/** — typed wrappers over `packages/data/food.json` (food/tool dictionary) and `pantry.json`
- **types/** — TypeScript type definitions
- **styles/** — SCSS including CSS variables (`css-vars.scss`)

### Key Patterns

- **CSR-only**: SSR is disabled in `apps/web/nuxt.config.ts`; app is statically generated
- **Client storage**: Dexie (IndexedDB) for favorites, history, user preferences — enables offline support
- **Styling**: UnoCSS with Wind preset (Tailwind-compatible), attributify mode, and custom shortcuts (`tag`, `btn`)
- **Dark mode**: Class-based via `@nuxtjs/color-mode` (classSuffix: '')
- **Icons**: Iconify with multiple packs (carbon, mdi, twemoji); food tool icons dynamically safelisted in `apps/web/uno.config.ts`

### Prerendered Routes

`/`, `/random`, `/help`, `/user`, `/404`, `/settings` are prerendered during static generation.

## Testing

Tests live in `test/` directory. Vitest uses jsdom environment with setup in `test/setup.ts`. Config in `vitest.config.ts`. Run a single test file:

```bash
pnpm test -- test/recipe.test.ts
```

## Code Style

Uses `@antfu/eslint-config` (flat config) with UnoCSS and formatters enabled. Pre-commit hook runs `lint-staged` via `simple-git-hooks`. Ignored paths include `packages/data/**/*.json`, `ios/`, `android/`, `dist/`.

## Environment Variables

See `.env.example`:

- `FEISHU_APP_ID` / `FEISHU_APP_SECRET` — For fetching recipe data from Feishu
- `APPLE_DEVELOPMENT_TEAM` — iOS signing

## Node/Package Manager

- Node.js: 22 or newer
- pnpm: 11.21.0 (enforced via `packageManager` field)
