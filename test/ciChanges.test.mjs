import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import process from 'node:process'
// eslint-disable-next-line test/no-import-node-test -- This bootstrap check runs before installing Vitest or workspace dependencies.
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { detectNativeChanges, planIosTests } from '../scripts/ci-changes.mjs'

function repository(run) {
  const cwd = mkdtempSync(join(tmpdir(), 'cook-ci-changes-'))
  const git = (...args) => execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim()
  const write = (path, content = path) => {
    mkdirSync(dirname(join(cwd, path)), { recursive: true })
    writeFileSync(join(cwd, path), content)
  }
  const commit = () => {
    git('add', '--all')
    git('-c', 'user.name=CI Test', '-c', 'user.email=ci@example.invalid', 'commit', '-qm', 'test fixture')
    return git('rev-parse', 'HEAD')
  }
  try {
    git('init', '-q')
    write('README.md')
    write('apps/ios/Cook/App.swift')
    write('docs/dev/old.md')
    const base = commit()
    run({ cwd, git, write, commit, base })
  }
  finally {
    rmSync(cwd, { recursive: true, force: true })
  }
}

test('PR documentation edits use the fixed merge base, ignoring unrelated base changes', () => {
  repository(({ cwd, git, write, commit, base }) => {
    git('checkout', '-qb', 'topic')
    write('docs/dev/ios-testflight.md')
    const head = commit()
    git('checkout', '--detach', base)
    write('apps/ios/Cook/App.swift', 'new main code')
    const newerBase = commit()
    const result = detectNativeChanges('pull_request', { pull_request: { base: { sha: newerBase }, head: { sha: head } } }, cwd)
    assert.equal(result.skipNative, true)
    assert.equal(result.changedFiles, 1)
  })
})

test('a multi-commit push includes earlier native code changes', () => {
  repository(({ cwd, write, commit, base }) => {
    write('apps/ios/Cook/App.swift', 'changed')
    commit()
    write('docs/dev/new.md')
    assert.equal(detectNativeChanges('push', { before: base, after: commit() }, cwd).skipNative, false)
  })
})

test('documentation deletion, rename, images and unusual file names can skip native jobs', () => {
  repository(({ cwd, write, commit, base }) => {
    rmSync(join(cwd, 'docs/dev/old.md'))
    renameSync(join(cwd, 'README.md'), join(cwd, 'CONTRIBUTING.md'))
    write('docs/dev/with spaces\nand newline.md')
    write('docs/public/example.png')
    assert.equal(detectNativeChanges('push', { before: base, after: commit() }, cwd).skipNative, true)
  })
})

test('the standalone Support workflow and its docs can skip native jobs, but mixed native changes cannot', () => {
  repository(({ cwd, write, commit, base }) => {
    write('.github/workflows/support-sync.yml')
    write('docs/dev/support-delivery-automation.md')
    const support = commit()
    assert.equal(detectNativeChanges('push', { before: base, after: support }, cwd).skipNative, true)
    write('packages/data/recipe.csv')
    const head = commit()
    assert.equal(detectNativeChanges('pull_request', { pull_request: { base: { sha: base }, head: { sha: head } } }, cwd).skipNative, false)
  })
})

test('moving source code into documentation still includes the removed source path', () => {
  repository(({ cwd, commit, base }) => {
    renameSync(join(cwd, 'apps/ios/Cook/App.swift'), join(cwd, 'docs/dev/moved.md'))
    assert.equal(detectNativeChanges('push', { before: base, after: commit() }, cwd).skipNative, false)
  })
})

test('browser UI, adapters and their tests need Web checks without simulator jobs', () => {
  repository(({ cwd, write, commit, base }) => {
    for (const path of [
      'apps/web/app/pages/my.vue',
      'apps/web/app/components/AccountStatus.vue',
      'apps/web/app/adapters/yunlefunAuth.ts',
      'apps/web/app/composables/useUserDataSync.ts',
      'apps/web/app/utils/userDataSyncSdk.ts',
      'test/yunlefunAuthAdapter.test.ts',
      'test/userDataSyncSdk.test.ts',
      'specs/user-data-sync/validation.md',
    ]) write(path)
    const after = commit()
    for (const [name, event] of [
      ['push', { before: base, after }],
      ['pull_request', { pull_request: { base: { sha: base }, head: { sha: after } } }],
    ]) assert.equal(detectNativeChanges(name, event, cwd).skipNative, true)
  })
})

test('shared contracts, resources, build configuration and unknown tests retain native coverage', () => {
  for (const path of [
    'apps/web/app/utils/userData.ts',
    'apps/web/app/utils/newProtocol.ts',
    'apps/web/app/data/food.ts',
    'apps/web/nuxt.config.ts',
    'packages/shared/links.json',
    'scripts/generate-user-data-contract.mjs',
    'cloudfunctions/cook-user-data/index.js',
    'test/userDataContract.test.ts',
    'test/newFeature.test.ts',
  ]) {
    repository(({ cwd, write, commit, base }) => {
      write('apps/web/app/pages/my.vue')
      write(path)
      assert.equal(detectNativeChanges('push', { before: base, after: commit() }, cwd).skipNative, false, path)
    })
  }
})

test('moving native code to a browser adapter still includes the removed native input', () => {
  repository(({ cwd, write, commit, base }) => {
    write('apps/web/app/adapters/.keep')
    renameSync(join(cwd, 'apps/ios/Cook/App.swift'), join(cwd, 'apps/web/app/adapters/moved.ts'))
    assert.equal(detectNativeChanges('push', { before: base, after: commit() }, cwd).skipNative, false)
  })
})

test('membership-only changes use financial coverage; mixed and unknown inputs retain core', () => {
  repository(({ cwd, write, commit, base }) => {
    write('apps/ios/Cook/Model/NativeMembershipStoreKit.swift')
    write('apps/ios/Cook/Views/MembershipView.swift')
    write('apps/ios/CookUITests/MembershipUITests.swift')
    write('apps/ios/CookCore/Sources/CookCore/MembershipPurchase.swift')
    write('apps/ios/CookCore/Tests/CookCoreTests/MembershipPurchaseTests.swift')
    write('apps/ios/StoreKitValidation/NativeMembershipStoreKitTests.swift')
    write('docs/dev/ios-membership.md')
    const after = commit()
    const result = detectNativeChanges('push', { before: base, after }, cwd)
    assert.equal(result.skipNative, false)
    assert.equal(result.scope, 'membership')
    assert.deepEqual(planIosTests('push', undefined, result.scope).suites, ['membership'])
    assert.deepEqual(planIosTests('pull_request', undefined, result.scope).suites, ['membership'])
    assert.deepEqual(planIosTests('workflow_dispatch', 'core', result.scope).suites, ['core-regression'])
    assert.deepEqual(planIosTests('push', undefined, result.scope).storekitDevices, ['iphone', 'ipad'])
    assert.deepEqual(planIosTests('workflow_dispatch', 'membership', 'core'), {
      suites: ['membership'],
      maxParallel: 2,
      storekitDevices: ['iphone', 'ipad'],
    })
    assert.equal(planIosTests('workflow_dispatch', 'unknown', result.scope).suites.length, 3)
    assert.equal(planIosTests('workflow_dispatch', 'full', result.scope).suites.length, 3)
    write('apps/ios/Cook/App.swift', 'mixed native change')
    assert.equal(detectNativeChanges('push', { before: base, after: commit() }, cwd).scope, 'core')
    write('apps/ios/project.yml')
    assert.equal(detectNativeChanges('push', { before: after, after: commit() }, cwd).scope, 'core')
  })
})

test('bundled privacy text, mixed changes and build inputs always require native jobs', () => {
  for (const path of ['docs/privacy.md', 'docs/PRIVACY.md', 'apps/ios/Resources/help.md', 'packages/data/recipe.csv', 'packages/cook/src/index.ts', 'pnpm-lock.yaml', 'package.json', '.github/workflows/ci.yml', 'scripts/ci-changes.mjs', 'docs/.vitepress/config.ts', 'unknown.md']) {
    repository(({ cwd, write, commit, base }) => {
      write(path)
      write('docs/dev/note.md')
      assert.equal(detectNativeChanges('push', { before: base, after: commit() }, cwd).skipNative, false, path)
    })
  }
})

test('the workflow entry point writes job outputs and defaults to native on invalid event JSON', () => {
  repository(({ cwd, write, commit, base }) => {
    write('docs/dev/new.md')
    const after = commit()
    const eventPath = join(cwd, 'event.json')
    const outputPath = join(cwd, 'output.txt')
    const summaryPath = join(cwd, 'summary.md')
    const entry = fileURLToPath(new URL('../scripts/ci-changes.mjs', import.meta.url))
    const env = { ...process.env, GITHUB_EVENT_NAME: 'pull_request', GITHUB_EVENT_PATH: eventPath, GITHUB_OUTPUT: outputPath, GITHUB_STEP_SUMMARY: summaryPath }
    writeFileSync(eventPath, JSON.stringify({ pull_request: { base: { sha: base }, head: { sha: after } } }))
    execFileSync(process.execPath, [entry], { cwd, env })
    const corePlan = 'ios_suites=["core-regression"]\nios_max_parallel=2\nios_storekit_devices=["iphone"]\n'
    assert.equal(readFileSync(outputPath, 'utf8'), `skip_native=true\n${corePlan}`)
    assert.match(readFileSync(summaryPath, 'utf8'), /not required \(only browser-owned inputs, documentation or the Support sync workflow changed\)/)
    for (const invalidEvent of ['{ invalid JSON', 'null']) {
      writeFileSync(eventPath, invalidEvent)
      execFileSync(process.execPath, [entry], { cwd, env })
    }
    assert.equal(readFileSync(outputPath, 'utf8'), `skip_native=true\n${corePlan}${`skip_native=false\n${corePlan}`.repeat(2)}`)
  })
})

test('a support-only push carries coverage only from a green push at the exact previous branch head', () => {
  repository(({ cwd, write, commit, base }) => {
    write('.github/workflows/support-sync.yml')
    const after = commit()
    const eventPath = join(cwd, 'event.json')
    writeFileSync(eventPath, JSON.stringify({ before: base, after, ref: 'refs/heads/main' }))
    const bin = join(cwd, 'bin')
    mkdirSync(bin)
    writeFileSync(join(bin, 'gh'), `#!/usr/bin/env node
if (process.env.FAKE_GH_OUTPUT === 'error') process.exit(1)
process.stdout.write(process.env.FAKE_GH_OUTPUT)
`, { mode: 0o755 })
    const entry = fileURLToPath(new URL('../scripts/ci-changes.mjs', import.meta.url))
    const passed = { head_sha: base, head_branch: 'main', event: 'push', conclusion: 'success' }
    for (const [response, expected] of [
      [{ workflow_runs: [passed] }, true],
      [{ workflow_runs: [] }, false],
      ...[{ conclusion: 'cancelled' }, { conclusion: 'failure' }, { conclusion: null }, { head_sha: after }, { head_branch: 'dev' }, { event: 'pull_request' }]
        .map(change => [{ workflow_runs: [{ ...passed, ...change }] }, false]),
      ['error', false],
    ]) {
      const env = {
        ...process.env,
        PATH: `${bin}:${process.env.PATH}`,
        GITHUB_REPOSITORY: 'example/cook',
        GITHUB_OUTPUT: join(cwd, 'push-output.txt'),
        GITHUB_STEP_SUMMARY: join(cwd, 'push-summary.md'),
        GITHUB_EVENT_NAME: 'push',
        GITHUB_EVENT_PATH: eventPath,
        FAKE_GH_OUTPUT: response === 'error' ? response : JSON.stringify(response),
      }
      const result = JSON.parse(execFileSync(process.execPath, [entry], { cwd, env, encoding: 'utf8' }))
      assert.equal(result.skipNative, expected)
    }
  })
})

test('empty diffs, missing objects, zero SHAs, malformed events and unsupported events require native jobs', () => {
  repository(({ cwd, base }) => {
    for (const [name, event] of [
      ['push', { before: base, after: base }],
      ['push', { before: 'f'.repeat(40), after: base }],
      ['push', { before: '0'.repeat(40), after: base }],
      ['pull_request', {}],
      ['push', null],
      ['workflow_dispatch', {}],
    ]) {
      assert.equal(detectNativeChanges(name, event, cwd).skipNative, false)
    }
  })
})
