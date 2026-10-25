import { cpSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

// Keep these paths aligned with apps/web/app/pages and its definePageMeta aliases.
const legacyRoutes = [
  '/home',
  '/tabs',
  '/my',
  '/library',
  '/apps',
  '/random',
  '/user',
  '/tabs/home',
  '/tabs/my',
  '/tabs/library',
  '/tabs/apps',
  '/apps/random',
  '/recipes',
  '/recipes/favorites',
  '/recipes/history',
  '/recipes/mine',
  '/recipes/new',
  '/cookbooks',
  '/cookbooks/new',
  '/catalog',
  '/settings',
  '/feedback',
  '/help',
  '/changelog',
  '/about',
  '/about/me',
  '/about/acknowledgements',
  '/auth/callback',
]
const protectedPaths = ['_nuxt', 'auth', '.well-known', 'sw.js', 'about']
const requiredWebFiles = ['index.html', 'sw.js', '.well-known/apple-app-site-association', 'auth/wechat/index.html', 'about/index.html', 'about/terms.html']
const defaultRoot = fileURLToPath(new URL('../', import.meta.url))

function requirePath(path, directory = false) {
  if (!existsSync(path) || !(directory ? statSync(path).isDirectory() : statSync(path).isFile()))
    throw new Error(`Missing ${directory ? 'directory' : 'file'}: ${path}. Build the web app and documentation first.`)
}

function contains(parent, child) {
  const path = relative(parent, child)
  return path === '' || (path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path))
}

function assertOutputPath(root, output) {
  if (output !== join(root, 'dist-site') || dirname(output) !== root)
    throw new Error(`Refusing unexpected output path: ${output}`)
  let info
  try {
    info = lstatSync(output)
  }
  catch (error) {
    if (error.code !== 'ENOENT')
      throw error
  }
  if (info && (info.isSymbolicLink() || realpathSync(output) !== output))
    throw new Error(`Refusing symlink output: ${output}`)
}

function assertStagingPath(root, staging) {
  if (dirname(staging) !== root || !basename(staging).startsWith('.dist-site-') || realpathSync(staging) !== staging)
    throw new Error(`Refusing unexpected staging path: ${staging}`)
}

function redirectDocument(destination, label) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${label} · 食用手册</title>
<link rel="canonical" href="https://cook.yunle.fun${destination}">
<meta http-equiv="refresh" content="0; url=${destination}">
</head>
<body><p>页面已迁移，请前往<a href="${destination}">${label}</a>。</p></body>
</html>
`
}

function assertAssetCompatibility(web, docs, path = 'assets') {
  const docsPath = join(docs, path)
  const webPath = join(web, path)
  if (!existsSync(docsPath) || !existsSync(webPath))
    return
  const docsDirectory = statSync(docsPath).isDirectory()
  const webDirectory = statSync(webPath).isDirectory()
  if (docsDirectory !== webDirectory)
    throw new Error(`Documentation asset conflicts with legacy app asset: ${path}`)
  if (docsDirectory) {
    for (const name of readdirSync(docsPath).sort())
      assertAssetCompatibility(web, docs, join(path, name))
  }
  else if (!readFileSync(docsPath).equals(readFileSync(webPath))) {
    throw new Error(`Documentation asset conflicts with legacy app asset: ${path}`)
  }
}

/**
 * Compose the product/docs site and same-origin legacy app into an isolated output.
 * Legacy marketing pages use HTML refresh links, without server redirect rules.
 *
 * @param {string} [repositoryRoot] Repository containing dist and docs/.vitepress/dist.
 * @returns {string} Absolute path of the completed dist-site directory.
 * @throws {Error} If build inputs are missing, routes collide, or output paths are unsafe.
 */
export function composeProductSite(repositoryRoot = defaultRoot) {
  const root = realpathSync(resolve(repositoryRoot))
  const web = join(root, 'dist')
  const docs = join(root, 'docs/.vitepress/dist')
  const output = join(root, 'dist-site')

  assertOutputPath(root, output)
  requirePath(web, true)
  requirePath(docs, true)
  requirePath(join(web, '_nuxt'), true)
  for (const path of requiredWebFiles)
    requirePath(join(web, path))
  for (const path of ['index.html', '404.html', 'terms.html'])
    requirePath(join(docs, path))
  requirePath(join(root, 'edgeone.json'))
  // Validate configuration before replacing an earlier successful composition.
  JSON.parse(readFileSync(join(root, 'edgeone.json'), 'utf8'))
  assertAssetCompatibility(web, docs)

  for (const source of [realpathSync(web), realpathSync(docs)]) {
    if (contains(output, source) || contains(source, output))
      throw new Error(`Build input overlaps output: ${source}`)
  }
  for (const path of protectedPaths) {
    if (existsSync(join(docs, path)))
      throw new Error(`Documentation output collides with legacy app resource: ${path}`)
  }
  for (const route of legacyRoutes) {
    for (const path of [`${route.slice(1)}/index.html`, `${route.slice(1)}.html`]) {
      if (existsSync(join(docs, path)))
        throw new Error(`Documentation output collides with legacy app route: ${route}`)
    }
  }

  const shell = readFileSync(join(web, existsSync(join(web, '200.html')) ? '200.html' : 'index.html'), 'utf8')
  const staging = mkdtempSync(join(root, '.dist-site-'))
  try {
    cpSync(web, staging, {
      recursive: true,
      dereference: true,
      // Unknown documentation paths must use VitePress's 404, not an app fallback.
      filter: source => source !== join(web, '200.html'),
    })
    cpSync(docs, staging, { recursive: true, dereference: true })
    for (const route of legacyRoutes) {
      const page = join(staging, route.slice(1), 'index.html')
      if (!existsSync(page)) {
        mkdirSync(dirname(page), { recursive: true })
        writeFileSync(page, shell)
      }
    }
    writeFileSync(join(staging, 'about/index.html'), redirectDocument('/', '食用手册官网'))
    writeFileSync(join(staging, 'about/terms.html'), redirectDocument('/terms', '用户协议'))
    cpSync(join(root, 'edgeone.json'), join(staging, 'edgeone.json'))
    assertOutputPath(root, output)
    assertStagingPath(root, staging)
    rmSync(output, { recursive: true, force: true })
    renameSync(staging, output)
    return output
  }
  finally {
    if (existsSync(staging)) {
      assertStagingPath(root, staging)
      rmSync(staging, { recursive: true, force: true })
    }
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url))
  console.log(`Composed product site at ${composeProductSite()}`)
