import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import process from 'node:process'
// eslint-disable-next-line test/no-import-node-test -- This bootstrap check runs before installing Vitest or workspace dependencies.
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { detectDocsOnly } from '../scripts/ci-changes.mjs'

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
    const result = detectDocsOnly('pull_request', { pull_request: { base: { sha: newerBase }, head: { sha: head } } }, cwd)
    assert.equal(result.docsOnly, true)
    assert.equal(result.changedFiles, 1)
  })
})

test('a multi-commit push includes earlier native code changes', () => {
  repository(({ cwd, write, commit, base }) => {
    write('apps/ios/Cook/App.swift', 'changed')
    commit()
    write('docs/dev/new.md')
    assert.equal(detectDocsOnly('push', { before: base, after: commit() }, cwd).docsOnly, false)
  })
})

test('documentation deletion, rename, images and unusual file names can skip native jobs', () => {
  repository(({ cwd, write, commit, base }) => {
    rmSync(join(cwd, 'docs/dev/old.md'))
    renameSync(join(cwd, 'README.md'), join(cwd, 'CONTRIBUTING.md'))
    write('docs/dev/with spaces\nand newline.md')
    write('docs/public/example.png')
    assert.equal(detectDocsOnly('push', { before: base, after: commit() }, cwd).docsOnly, true)
  })
})

test('moving source code into documentation still includes the removed source path', () => {
  repository(({ cwd, commit, base }) => {
    renameSync(join(cwd, 'apps/ios/Cook/App.swift'), join(cwd, 'docs/dev/moved.md'))
    assert.equal(detectDocsOnly('push', { before: base, after: commit() }, cwd).docsOnly, false)
  })
})

test('bundled privacy text, mixed changes and build inputs always require native jobs', () => {
  for (const path of ['docs/privacy.md', 'docs/PRIVACY.md', 'apps/ios/Resources/help.md', 'packages/data/recipe.csv', 'packages/cook/src/index.ts', 'pnpm-lock.yaml', 'package.json', '.github/workflows/ci.yml', 'scripts/ci-changes.mjs', 'docs/.vitepress/config.ts', 'unknown.md']) {
    repository(({ cwd, write, commit, base }) => {
      write(path)
      write('docs/dev/note.md')
      assert.equal(detectDocsOnly('push', { before: base, after: commit() }, cwd).docsOnly, false, path)
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
    const env = { ...process.env, GITHUB_EVENT_NAME: 'push', GITHUB_EVENT_PATH: eventPath, GITHUB_OUTPUT: outputPath, GITHUB_STEP_SUMMARY: summaryPath }
    writeFileSync(eventPath, JSON.stringify({ before: base, after }))
    execFileSync(process.execPath, [entry], { cwd, env })
    assert.equal(readFileSync(outputPath, 'utf8'), 'docs_only=true\n')
    assert.match(readFileSync(summaryPath, 'utf8'), /skipped \(documentation only\)/)
    writeFileSync(eventPath, '{ invalid JSON')
    execFileSync(process.execPath, [entry], { cwd, env })
    assert.equal(readFileSync(outputPath, 'utf8'), 'docs_only=true\ndocs_only=false\n')
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
      assert.equal(detectDocsOnly(name, event, cwd).docsOnly, false)
    }
  })
})
