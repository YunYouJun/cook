import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
// eslint-disable-next-line test/no-import-node-test -- The composer is a standalone build tool tested without Nuxt or Vitest startup.
import { test } from 'node:test'
import { composeProductSite } from '../scripts/compose-product-site.mjs'

const inputs = {
  'dist/index.html': '<div id="__nuxt">app shell</div>',
  'dist/200.html': '<div id="__nuxt">fallback shell</div>',
  'dist/404.html': 'old app 404',
  'dist/_nuxt/app.js': 'app bundle',
  'dist/assets/app-only.js': 'legacy public app asset',
  'dist/assets/shared/icon.svg': 'identical shared icon',
  'dist/auth/callback/index.html': 'existing same-origin callback',
  'dist/auth/wechat/index.html': 'native callback fallback',
  'dist/.well-known/apple-app-site-association': '{"applinks":{}}',
  'dist/sw.js': 'retire the old service worker',
  'dist/about/index.html': 'submitted product snapshot',
  'dist/about/terms.html': 'existing agreement',
  'dist/about/site.css': 'agreement styles',
  'dist/about/assets/icon.svg': 'agreement icon',
  'dist/settings/index.html': 'existing settings shell',
  'docs/.vitepress/dist/index.html': '<h1>creative product landing</h1>',
  'docs/.vitepress/dist/404.html': 'documentation 404',
  'docs/.vitepress/dist/assets/site.js': 'documentation bundle',
  'docs/.vitepress/dist/assets/shared/icon.svg': 'identical shared icon',
  'docs/.vitepress/dist/terms.html': 'current product agreement',
  'docs/.vitepress/dist/privacy.html': 'privacy policy',
  'docs/.vitepress/dist/guide/start.html': 'getting started',
  'edgeone.json': '{"headers":[{"source":"/auth/wechat/*","headers":[{"key":"Referrer-Policy","value":"no-referrer"}]}]}',
}

function fixture(run, omitted = []) {
  const temporaryParent = realpathSync(tmpdir())
  const root = realpathSync(mkdtempSync(join(temporaryParent, 'cook-product-site-')))
  const write = (path, content) => {
    mkdirSync(dirname(join(root, path)), { recursive: true })
    writeFileSync(join(root, path), content)
  }
  try {
    for (const [path, content] of Object.entries(inputs)) {
      if (!omitted.some(prefix => path.startsWith(prefix)))
        write(path, content)
    }
    run({ root, write, read: path => readFileSync(join(root, path), 'utf8') })
  }
  finally {
    assert.equal(dirname(root), temporaryParent)
    assert.ok(basename(root).startsWith('cook-product-site-'))
    assert.equal(realpathSync(root), root)
    rmSync(root, { recursive: true, force: true })
  }
}

test('documentation owns root and unknown-page HTML while legacy resources survive unchanged', () => {
  fixture(({ root, read }) => {
    assert.equal(composeProductSite(root), join(root, 'dist-site'))
    assert.equal(read('dist-site/index.html'), inputs['docs/.vitepress/dist/index.html'])
    assert.equal(read('dist-site/404.html'), 'documentation 404')
    assert.equal(read('dist-site/guide/start.html'), 'getting started')
    assert.equal(read('dist-site/privacy.html'), 'privacy policy')
    assert.equal(read('dist-site/assets/site.js'), 'documentation bundle')
    assert.equal(read('dist-site/assets/app-only.js'), 'legacy public app asset')
    assert.equal(read('dist-site/assets/shared/icon.svg'), 'identical shared icon')
    assert.equal(read('dist-site/terms.html'), 'current product agreement')
    assert.deepEqual(JSON.parse(read('dist-site/edgeone.json')).headers[0], JSON.parse(inputs['edgeone.json']).headers[0])
    for (const path of ['_nuxt/app.js', 'auth/callback/index.html', 'auth/wechat/index.html', '.well-known/apple-app-site-association', 'sw.js', 'about/index.html', 'about/terms.html', 'about/site.css', 'about/assets/icon.svg', 'settings/index.html'])
      assert.equal(read(`dist-site/${path}`), inputs[`dist/${path}`], path)
    assert.equal(existsSync(join(root, 'dist-site/200.html')), false)
    assert.equal(existsSync(join(root, 'dist-site/unknown-doc/index.html')), false)
    for (const [path, content] of Object.entries(inputs))
      assert.equal(read(path), content, `input must remain unchanged: ${path}`)
    assert.equal(existsSync(join(root, 'dist/home')), false)
  })
})

test('product-only rules redirect legacy marketing URLs and protect same-origin callbacks', () => {
  fixture(({ root, write, read }) => {
    const original = {
      ...JSON.parse(inputs['edgeone.json']),
      redirects: [{ source: '/old-news', destination: '/news', statusCode: 302 }],
      buildCommand: 'pnpm build',
    }
    const originalText = JSON.stringify(original)
    write('edgeone.json', originalText)
    composeProductSite(root)
    const config = JSON.parse(read('dist-site/edgeone.json'))
    assert.equal(read('edgeone.json'), originalText)
    assert.equal(config.buildCommand, 'pnpm build')
    assert.deepEqual(config.redirects, [
      ...original.redirects,
      { source: '/about', destination: '/', statusCode: 301 },
      { source: '/about/', destination: '/', statusCode: 301 },
      { source: '/about/index.html', destination: '/', statusCode: 301 },
      { source: '/about/terms.html', destination: '/terms', statusCode: 301 },
    ])
    assert.deepEqual(config.headers.slice(0, original.headers.length), original.headers)
    for (const source of ['/auth/callback', '/auth/callback/*']) {
      assert.deepEqual(config.headers.find(rule => rule.source === source)?.headers, [
        { key: 'Cache-Control', value: 'no-store' },
        { key: 'Referrer-Policy', value: 'no-referrer' },
      ])
    }
    assert.equal(config.redirects.some(rule => rule.source.startsWith('/auth') || rule.source === '/about/*'), false)
    assert.equal(read('dist-site/auth/callback/index.html'), inputs['dist/auth/callback/index.html'])
    assert.equal(read('dist-site/about/me/index.html'), inputs['dist/200.html'])
    assert.equal(read('dist/about/index.html'), 'submitted product snapshot')
    assert.equal(read('dist/about/terms.html'), 'existing agreement')
  })
})

test('different contents or file-directory conflicts at shared asset URLs fail before replacing output', () => {
  for (const collision of ['assets/shared/icon.svg', 'assets/shared']) {
    fixture(({ root, write, read }) => {
      write(`docs/.vitepress/dist/${collision}`, 'would break the existing app')
      write('dist-site/index.html', 'previous successful build')
      assert.throws(() => composeProductSite(root), /asset conflicts with legacy app asset/)
      assert.equal(read('dist-site/index.html'), 'previous successful build')
      assert.equal(read('dist/assets/shared/icon.svg'), 'identical shared icon')
    }, collision === 'assets/shared' ? ['docs/.vitepress/dist/assets/shared/'] : [])
  }
})

test('explicit app routes and aliases get fallback shells without replacing prerendered routes', () => {
  fixture(({ root, read }) => {
    composeProductSite(root)
    for (const route of ['home', 'tabs', 'my', 'library', 'apps', 'random', 'user', 'tabs/home', 'tabs/my', 'tabs/library', 'tabs/apps', 'apps/random', 'recipes', 'recipes/favorites', 'recipes/history', 'recipes/mine', 'recipes/new', 'cookbooks', 'cookbooks/new', 'catalog', 'feedback', 'help', 'changelog', 'about/me', 'about/acknowledgements'])
      assert.equal(read(`dist-site/${route}/index.html`), inputs['dist/200.html'], route)
    assert.equal(read('dist-site/settings/index.html'), 'existing settings shell')
    assert.equal(read('dist-site/auth/callback/index.html'), 'existing same-origin callback')
  })
})

test('root app shell supplies compatibility routes when no 200 shell or callback was generated', () => {
  fixture(({ root, read }) => {
    composeProductSite(root)
    assert.equal(read('dist-site/home/index.html'), inputs['dist/index.html'])
    assert.equal(read('dist-site/auth/callback/index.html'), inputs['dist/index.html'])
  }, ['dist/200.html', 'dist/auth/callback/'])
})

test('missing build inputs fail before replacing an existing output', () => {
  for (const missing of ['dist/index.html', 'dist/_nuxt/', 'dist/sw.js', 'dist/.well-known/', 'dist/auth/wechat/', 'dist/about/terms.html', 'docs/.vitepress/dist/', 'docs/.vitepress/dist/index.html', 'docs/.vitepress/dist/404.html', 'docs/.vitepress/dist/terms.html', 'edgeone.json']) {
    fixture(({ root, write, read }) => {
      write('dist-site/index.html', 'previous successful build')
      assert.throws(() => composeProductSite(root), /Missing (file|directory)/, missing)
      assert.equal(read('dist-site/index.html'), 'previous successful build', missing)
    }, [missing])
  }
})

test('documentation cannot overwrite callback resources or app route entry points', () => {
  for (const collision of ['_nuxt/app.js', 'auth/callback/index.html', '.well-known/apple-app-site-association', 'sw.js', 'about/index.html', 'settings/index.html', 'recipes.html']) {
    fixture(({ root, write, read }) => {
      write(`docs/.vitepress/dist/${collision}`, 'conflicting documentation output')
      write('dist-site/index.html', 'previous successful build')
      assert.throws(() => composeProductSite(root), /collides with legacy app/, collision)
      assert.equal(read('dist-site/index.html'), 'previous successful build')
      assert.equal(read('dist/index.html'), inputs['dist/index.html'])
    })
  }
})

test('symlink output cannot delete a source build, including a dangling target', () => {
  for (const target of ['dist', 'docs/.vitepress/dist', 'missing-output']) {
    fixture(({ root, read }) => {
      symlinkSync(join(root, target), join(root, 'dist-site'), 'dir')
      assert.throws(() => composeProductSite(root), /Refusing symlink output/)
      assert.equal(read('dist/index.html'), inputs['dist/index.html'])
      assert.equal(read('docs/.vitepress/dist/index.html'), inputs['docs/.vitepress/dist/index.html'])
    })
  }
})

test('a completed rebuild replaces stale output without changing either source build', () => {
  fixture(({ root, write, read }) => {
    composeProductSite(root)
    write('dist-site/stale.html', 'obsolete')
    composeProductSite(root)
    assert.equal(existsSync(join(root, 'dist-site/stale.html')), false)
    assert.equal(read('dist-site/index.html'), inputs['docs/.vitepress/dist/index.html'])
    assert.equal(read('dist/index.html'), inputs['dist/index.html'])
    assert.equal(read('docs/.vitepress/dist/index.html'), inputs['docs/.vitepress/dist/index.html'])
  })
})
