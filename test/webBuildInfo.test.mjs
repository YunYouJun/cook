import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
// eslint-disable-next-line test/no-import-node-test -- Build provenance is a standalone Node tool without Nuxt or Vitest startup.
import { test } from 'node:test'
import { createAndroidLinksDocument, parseApkSignerFingerprints, verifyAndroidDeviceLinks, verifyAndroidLinks } from '../scripts/lib/android-app-links.mjs'
import { BUILD_INFO_PATH, buildWebOutput, captureBuildInput, copyWebOutput, createBuildInfo } from '../scripts/lib/web-build.mjs'

const builtAt = '2026-10-28T12:00:00.000Z'
const now = () => new Date(builtAt)
const assetLinksPath = 'dist/.well-known/assetlinks.json'

function fingerprint(byte) {
  return Array.from({ length: 32 }).fill(byte).join(':')
}

test('reads certificate digests from older and versioned apksigner output formats', () => {
  const output = [
    `Signer #1 certificate SHA-256 digest: ${'a1'.repeat(32)}`,
    `V3.0 Signer: certificate SHA-256 digest: ${'a1'.repeat(32)}`,
    `V3.1 Signer: certificate SHA-256 digest: ${'b2'.repeat(32)}`,
    `V3.1 Signer: certificate SHA-1 digest: ${'a1'.repeat(20)}`,
  ].join('\r\n')
  assert.deepEqual(parseApkSignerFingerprints(output), [fingerprint('A1'), fingerprint('B2')])
})

test('default Android debug signers cannot be used for a production association', () => {
  for (const signer of ['Signer #1 certificate', 'V2 Signer: certificate']) {
    const output = [
      `${signer} DN: C=US, O=Android, CN=Android Debug`,
      `${signer} SHA-256 digest: ${'a1'.repeat(32)}`,
    ].join('\r\n')
    assert.throws(() => parseApkSignerFingerprints(output), /Android Debug certificate/)
  }
})

test('published Android associations must match the intended signer on both production hosts', async () => {
  const wanted = fingerprint('A1')
  const other = fingerprint('B2')
  const requests = []
  const statuses = await verifyAndroidLinks([wanted], async (url, request) => {
    assert.equal(request.redirect, 'manual')
    const host = new URL(url).hostname
    requests.push(host)
    return Response.json(createAndroidLinksDocument(host === 'cook.yunle.fun' ? [wanted, other] : [other]))
  })
  assert.deepEqual(requests.sort(), ['cook.yunle.fun', 'cook.yunyoujun.cn'])
  assert.deepEqual(statuses.map(result => result.ok), [true, false])
  assert.match(statuses[1].message, /Missing signing certificate/)
})

test('complete release certificate configuration rejects an extra signer on either host', async () => {
  const direct = fingerprint('A1')
  const play = fingerprint('B2')
  const unexpected = fingerprint('C3')
  const fetchDocument = async url => Response.json(createAndroidLinksDocument(
    new URL(url).hostname === 'cook.yunle.fun' ? [play, direct] : [play, unexpected, direct],
  ))
  const statuses = await verifyAndroidLinks([direct, play], fetchDocument, { requireExactFingerprints: true })
  assert.deepEqual(statuses.map(result => result.ok), [true, false])
  assert.equal(statuses[1].message, `Unexpected signing certificate: ${unexpected}`)
})

test('redirected and HTML responses cannot pass Android publishing verification', async () => {
  const wanted = fingerprint('A1')
  const statuses = await verifyAndroidLinks([wanted], async url => new URL(url).hostname === 'cook.yunle.fun'
    ? new Response(null, { status: 302, headers: { location: 'https://cook.yunyoujun.cn/.well-known/assetlinks.json' } })
    : new Response('<html>Cook</html>', { headers: { 'Content-Type': 'text/html' } }))
  assert.deepEqual(statuses.map(result => result.ok), [false, false])
  assert.match(statuses[0].message, /HTTP 200, got 302/)
  assert.match(statuses[1].message, /Expected application\/json, got text\/html/)
})

test('a certificate observation needs the correct Android package, relation and expected signer', async () => {
  const document = createAndroidLinksDocument([fingerprint('A1')])
  const withoutSigner = await verifyAndroidLinks([], async () => Response.json(document))
  assert.equal(withoutSigner.every(result => !result.ok && result.message.includes('supply the installed APK')), true)

  const [statement] = document
  const wrongTarget = { ...statement, target: { ...statement.target, package_name: 'fun.yunle.cook.debug' } }
  const wrongRelation = { ...statement, relation: ['delegate_permission/common.get_login_creds'] }
  const statuses = await verifyAndroidLinks([fingerprint('A1')], async () => Response.json([wrongTarget, wrongRelation]))
  assert.equal(statuses.every(result => !result.ok && result.message.includes('No valid direct association')), true)
})

test('published Android associations require the current route restrictions as well as the signer', async () => {
  const wanted = fingerprint('A1')
  const [current] = createAndroidLinksDocument([wanted])
  const legacy = { relation: current.relation, target: current.target }
  const statuses = await verifyAndroidLinks([wanted], async url => Response.json(
    new URL(url).hostname === 'cook.yunle.fun' ? [current] : [current, legacy],
  ))
  assert.deepEqual(statuses.map(result => result.ok), [true, false])
  assert.match(statuses[1].message, /Dynamic App Links rules/)

  const unrestrictedRecipeLinks = structuredClone(current)
  const components = unrestrictedRecipeLinks.relation_extensions[current.relation[0]].dynamic_app_link_components
  for (const rule of components.filter(rule => rule['/'].startsWith('/recipes/share')))
    delete rule['?']
  const oldRecipeRoutes = await verifyAndroidLinks([wanted], async url => Response.json(
    new URL(url).hostname === 'cook.yunle.fun' ? [unrestrictedRecipeLinks] : [current],
  ))
  assert.deepEqual(oldRecipeRoutes.map(result => result.ok), [false, true])
})

test('device App Links verification checks the two verified domains and resolves only synthetic URLs', () => {
  const resolved = []
  const results = verifyAndroidDeviceLinks({
    run(args) {
      if (args[1] === 'getprop')
        return '35\r\n'
      if (args[1] === 'pm') {
        assert.deepEqual(args, ['shell', 'pm', 'get-app-links', '--user', 'current', 'fun.yunle.cook'])
        return [
          '  fun.yunle.cook:',
          '    Domain verification state:',
          '      cook.yunyoujun.cn: verified',
          '      cook.yunle.fun: verified',
          '    User 0:',
          '      Verification link handling allowed: true',
        ].join('\r\n')
      }
      assert.deepEqual(args.slice(0, -1), [
        'shell',
        'cmd',
        'package',
        'resolve-activity',
        '--brief',
        '--user',
        'current',
        '--query-flags',
        '65536',
        '-a',
        'android.intent.action.VIEW',
        '-c',
        'android.intent.category.BROWSABLE',
        '-d',
      ])
      resolved.push(args.at(-1))
      return 'priority=0 preferredOrder=0 match=0x0 isDefault=true\r\nfun.yunle.cook/cn.yunyoujun.cook.MainActivity\r\n'
    },
  })
  assert.deepEqual(resolved, [
    '\'https://cook.yunle.fun/recipes/share/?id=250\'',
    '\'https://cook.yunle.fun/recipes/share?id=250\'',
    '\'https://cook.yunyoujun.cn/auth/callback?platform=native#ylf_sso=qa\'',
    '\'https://cook.yunyoujun.cn/auth/callback?platform=native#cook_handoff=qa\'',
  ])
  assert.equal(results.length, 6)
  assert.ok(results.every(result => result.ok))
})

test('manual selection cannot pass device verification and a verified domain can still open in a browser', () => {
  const results = verifyAndroidDeviceLinks({
    run(args) {
      if (args[1] === 'getprop')
        return '31'
      if (args[1] === 'pm') {
        return [
          '  fun.yunle.cook:',
          '    Domain verification state:',
          '      cook.yunle.fun: approved',
          '      cook.yunyoujun.cn: verified',
          '    User 10:',
          '      Verification link handling allowed: false',
          '      Selection state:',
          '        Enabled:',
          '          cook.yunle.fun',
        ].join('\n')
      }
      return 'priority=0 preferredOrder=0 match=0x0 isDefault=false\ncom.android.chrome/com.google.android.apps.chrome.Main\n'
    },
  })
  assert.deepEqual(results.map(result => result.ok), [false, true, false, false, false, false, false])
  assert.match(results[0].message, /got approved/)
  assert.match(results[2].message, /Open supported links is disabled/)
  assert.match(results[3].message, /com\.android\.chrome/)
  assert.throws(() => verifyAndroidDeviceLinks({ run: () => '30' }), /requires Android 12/)
})

function fixture(run, withGit = true) {
  const temporaryParent = realpathSync(tmpdir())
  const root = realpathSync(mkdtempSync(join(temporaryParent, 'cook-build-info-')))
  const write = (path, content) => {
    mkdirSync(dirname(join(root, path)), { recursive: true })
    writeFileSync(join(root, path), content)
  }
  const git = (...args) => execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  const commit = () => {
    git('add', '.')
    git('-c', 'user.name=Cook Test', '-c', 'user.email=cook-test@example.invalid', 'commit', '--quiet', '-m', 'test: create build fixture')
  }
  const generate = () => {
    write('apps/web/dist/index.html', '<h1>generated app</h1>')
    write('apps/web/dist/_nuxt/app.js', 'generated bundle')
    write('apps/web/dist/.well-known/apple-app-site-association', '{"applinks":{}}')
  }
  try {
    write('.gitignore', '/apps/web/dist/\n/dist/\n/node_modules/\n')
    write('package.json', JSON.stringify({ name: 'cook', version: '2.0.0-beta.18' }))
    write('edgeone.json', '{"headers":[]}')
    write('apps/web/app.vue', '<template>Cook</template>')
    write('packages/data/recipe.csv', 'name,stuff\ncommitted recipe,土豆\n')
    if (withGit) {
      git('init', '--quiet', '--initial-branch=main')
      commit()
    }
    run({ root, write, git, commit, generate, read: path => readFileSync(join(root, path), 'utf8') })
  }
  finally {
    assert.equal(dirname(root), temporaryParent)
    assert.ok(basename(root).startsWith('cook-build-info-'))
    assert.equal(realpathSync(root), root)
    rmSync(root, { recursive: true, force: true })
  }
}

test('clean Git builds expose only a fixed public allowlist and full source SHA', () => {
  fixture(({ root, git }) => {
    const sha = git('rev-parse', 'HEAD')
    const environment = {
      GITHUB_SHA: sha,
      CF_PAGES_COMMIT_SHA: sha.toUpperCase(),
      PRIVATE_KEY: 'private-key-must-never-be-public',
      SUPPORT_TICKET_URL: 'https://support.example.invalid/ticket#access=secret',
      HOME: '/private/test/home',
    }
    const input = captureBuildInput(root, environment)
    assert.ok(Object.isFrozen(input))
    assert.deepEqual(createBuildInfo(root, input, { environment, now }), {
      schemaVersion: 1,
      productId: 'cook',
      repository: 'YunYouJun/cook',
      commitSha: sha,
      version: '2.0.0-beta.18',
      builtAt,
      dirty: false,
    })
    const text = JSON.stringify(createBuildInfo(root, { ...input, ticket: environment.SUPPORT_TICKET_URL }, { environment, now }))
    for (const secret of [root, environment.PRIVATE_KEY, environment.SUPPORT_TICKET_URL, environment.HOME])
      assert.equal(text.includes(secret), false)
  })
})

test('provider SHA must be complete and agree with the actual Git checkout', () => {
  fixture(({ root }) => {
    for (const key of ['GITHUB_SHA', 'CF_PAGES_COMMIT_SHA']) {
      assert.throws(() => captureBuildInput(root, { [key]: 'f'.repeat(40) }), new RegExp(`disagrees with ${key}`))
      for (const invalid of ['abc123', 'refs/heads/main', 'secret-value', 'a'.repeat(64)])
        assert.throws(() => captureBuildInput(root, { [key]: invalid }), /complete 40-character Git SHA/)
    }
  })
})

test('a missing checkout and a nested directory cannot be used to invent provenance', () => {
  fixture(({ root }) => assert.throws(() => captureBuildInput(root, {}), /requires a Git checkout/), false)
  fixture(({ root, write }) => {
    write('nested/package.json', '{"version":"2.0.0"}')
    assert.throws(() => captureBuildInput(join(root, 'nested'), {}), /repository root/)
  })
})

test('dirty before or after generation remains explicitly ineligible for production verification', () => {
  fixture(({ root, write }) => {
    const clean = captureBuildInput(root, {})
    write('apps/web/app.vue', '<template>local modification</template>')
    assert.equal(createBuildInfo(root, clean, { environment: {}, now }).dirty, true)
    const dirty = captureBuildInput(root, {})
    write('apps/web/app.vue', '<template>Cook</template>')
    assert.equal(createBuildInfo(root, dirty, { environment: {}, now }).dirty, true)
    write('untracked-private-input.txt', 'local only')
    assert.equal(captureBuildInput(root, {}).dirty, true)
  })
})

test('Android App Links are generated only from explicitly supplied release fingerprints', () => {
  fixture(({ root, write, generate, read }) => {
    const first = Array.from({ length: 32 }).fill('AE').join(':')
    const second = Array.from({ length: 32 }).fill('4C').join(':')
    const input = captureBuildInput(root, {})
    generate()
    write(`apps/web/${assetLinksPath}`, '{"staleGeneratedAssociation":true}')
    copyWebOutput(root, input, {
      environment: { COOK_ANDROID_LINKS_SHA256: `${first.toLowerCase()}, ${first}, ${second}` },
      now,
    })
    assert.deepEqual(JSON.parse(read(assetLinksPath)), [{
      relation: ['delegate_permission/common.handle_all_urls'],
      target: {
        namespace: 'android_app',
        package_name: 'fun.yunle.cook',
        sha256_cert_fingerprints: [first, second],
      },
      relation_extensions: {
        'delegate_permission/common.handle_all_urls': {
          dynamic_app_link_components: [
            { '/': '/recipes/share', '?': { id: '?*' } },
            { '/': '/recipes/share/', '?': { id: '?*' } },
            { '/': '/auth/callback', '?': { platform: 'native' }, '#': 'ylf_sso=?*' },
            { '/': '/auth/callback', '?': { platform: 'native' }, '#': 'cook_handoff=?*' },
            { '/': '*', 'exclude': true },
          ],
        },
      },
    }])

    copyWebOutput(root, input, { environment: {}, now })
    assert.equal(existsSync(join(root, assetLinksPath)), false)
  })
})

test('invalid Android certificate configuration preserves the previous deployable output', () => {
  fixture(({ root, write, generate, read }) => {
    const input = captureBuildInput(root, {})
    write('dist/index.html', 'last successful build')
    generate()
    assert.throws(() => copyWebOutput(root, input, {
      environment: { COOK_ANDROID_LINKS_SHA256: '44:zz' },
      now,
    }), /COOK_ANDROID_LINKS_SHA256/)
    assert.equal(read('dist/index.html'), 'last successful build')
  })
})

test('version or HEAD changes during generation fail before replacing previous deployment output', () => {
  for (const change of ['version', 'commit']) {
    fixture(({ root, write, commit, generate, read }) => {
      write('dist/index.html', 'previous successful build')
      assert.throws(() => buildWebOutput(root, {
        environment: {},
        now,
        generate: () => {
          generate()
          if (change === 'version') {
            write('package.json', '{"version":"2.0.1"}')
          }
          else {
            write('apps/web/app.vue', '<template>new committed source</template>')
            commit()
          }
        },
      }), /Build source changed during generation/)
      assert.equal(read('dist/index.html'), 'previous successful build')
    })
  }
})

test('generated assets and routing config are copied with freshly verified metadata', () => {
  fixture(({ root, write, generate, read }) => {
    const expectedSha = captureBuildInput(root, {}).commitSha
    const result = buildWebOutput(root, {
      environment: {},
      now,
      generate: () => {
        generate()
        write(`apps/web/dist/${BUILD_INFO_PATH}`, '{"commitSha":"stale"}')
      },
    })
    assert.equal(result, join(root, 'dist'))
    assert.equal(read('dist/index.html'), '<h1>generated app</h1>')
    assert.equal(read('dist/_nuxt/app.js'), 'generated bundle')
    assert.equal(read('dist/edgeone.json'), '{"headers":[]}')
    assert.equal(read('dist/.well-known/apple-app-site-association'), '{"applinks":{}}')
    assert.deepEqual(JSON.parse(read(`dist/${BUILD_INFO_PATH}`)), {
      schemaVersion: 1,
      productId: 'cook',
      repository: 'YunYouJun/cook',
      commitSha: expectedSha,
      version: '2.0.0-beta.18',
      builtAt,
      dirty: false,
    })
    assert.equal(read(`apps/web/dist/${BUILD_INFO_PATH}`), '{"commitSha":"stale"}')
    assert.equal(existsSync(join(root, 'node_modules')), false, 'no checkpoint or cache is created')
  })
})

test('failed generation and missing source snapshot leave the last output intact', () => {
  fixture(({ root, write, generate, read }) => {
    write('dist/index.html', 'previous successful build')
    assert.throws(() => buildWebOutput(root, {
      environment: {},
      generate: () => {
        throw new Error('generation failed')
      },
    }), /generation failed/)
    assert.equal(read('dist/index.html'), 'previous successful build')
    generate()
    assert.throws(() => copyWebOutput(root, undefined, { environment: {} }), /pre-generation source snapshot/)
    assert.equal(read('dist/index.html'), 'previous successful build')
    assert.equal(existsSync(join(root, 'node_modules')), false)
  })
})

test('build modes prepare data under the internal preserve flag without dirtying clean inputs', () => {
  for (const [mode, expected] of [['build', ['convert']], ['full', ['fetch', 'convert']], ['generate', []]]) {
    fixture(({ root, generate, read }) => {
      const calls = []
      const environment = { COOK_PRESERVE_SOURCE_TIMESTAMP: 'false' }
      buildWebOutput(root, {
        mode,
        environment,
        now,
        prepare: (cwd, args, env) => {
          assert.equal(cwd, root)
          assert.equal(env.COOK_PRESERVE_SOURCE_TIMESTAMP, 'true')
          calls.push(args[1])
        },
        generate: (cwd, env) => {
          assert.equal(env.COOK_PRESERVE_SOURCE_TIMESTAMP, 'true')
          generate()
        },
      })
      assert.deepEqual(calls, expected)
      assert.equal(environment.COOK_PRESERVE_SOURCE_TIMESTAMP, 'false', 'caller environment remains untouched')
      assert.equal(JSON.parse(read(`dist/${BUILD_INFO_PATH}`)).dirty, false)
    })
  }
})

test('tracked preparation changes and existing dirty inputs cannot be hidden by conversion', () => {
  fixture(({ root, write, generate, read }) => {
    buildWebOutput(root, {
      mode: 'full',
      environment: {},
      now,
      prepare: (cwd, args) => {
        if (args[1] === 'fetch')
          write('packages/data/recipe.csv', 'new fetched source requires a commit')
      },
      generate,
    })
    assert.equal(JSON.parse(read(`dist/${BUILD_INFO_PATH}`)).dirty, true)
  })
  fixture(({ root, write, generate, read }) => {
    write('apps/web/app.vue', '<template>existing local change</template>')
    buildWebOutput(root, {
      mode: 'build',
      environment: {},
      now,
      prepare: () => write('apps/web/app.vue', '<template>Cook</template>'),
      generate,
    })
    assert.equal(JSON.parse(read(`dist/${BUILD_INFO_PATH}`)).dirty, true)
  })
})

test('a source commit changed during data preparation never reaches generation', () => {
  fixture(({ root, write, commit }) => {
    let generated = false
    assert.throws(() => buildWebOutput(root, {
      mode: 'build',
      environment: {},
      prepare: () => {
        write('apps/web/app.vue', '<template>new source commit</template>')
        commit()
      },
      generate: () => generated = true,
    }), /Build source changed during data preparation/)
    assert.equal(generated, false)
  })
})
