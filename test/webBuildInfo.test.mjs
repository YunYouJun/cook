import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join } from 'node:path'
// eslint-disable-next-line test/no-import-node-test -- Build provenance is a standalone Node tool without Nuxt or Vitest startup.
import { test } from 'node:test'
import { BUILD_INFO_PATH, buildWebOutput, captureBuildInput, copyWebOutput, createBuildInfo } from '../scripts/lib/web-build.mjs'

const builtAt = '2026-10-28T12:00:00.000Z'
const now = () => new Date(builtAt)

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
