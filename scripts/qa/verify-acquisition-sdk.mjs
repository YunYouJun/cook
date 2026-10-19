import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { lstatSync, mkdtempSync, readdirSync, renameSync, rmSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const argument = process.argv.find(value => value.startsWith('--sdk-root='))
assert(argument, 'Pass --sdk-root=/path/to/ylf (a local checkout containing packages/sso)')
const sdkRoot = resolve(argument.slice('--sdk-root='.length))
const cookRoot = fileURLToPath(new URL('../../', import.meta.url))
const dependency = join(cookRoot, 'node_modules/@yunlefun/sso')
assert(lstatSync(dependency).isSymbolicLink(), 'Expected the existing pnpm SSO dependency symlink')
const temporary = mkdtempSync(join(tmpdir(), 'cook-acquisition-sdk-'))
const previous = join(temporary, 'previous-sso-link')
let replaced = false
const run = (args, cwd) => execFileSync('pnpm', args, { cwd, stdio: 'inherit' })
// Invoke installed tools directly: newer pnpm can auto-install on `exec` when the release lock is pending.
function runCookTool(tool, args) {
  return execFileSync(join(cookRoot, 'node_modules/.bin', tool), args, {
    cwd: cookRoot,
    stdio: 'inherit',
  })
}

try {
  run(['--filter', '@yunlefun/sso', 'build'], sdkRoot)
  run(['pack', '--pack-destination', temporary], join(sdkRoot, 'packages/sso'))
  const archive = readdirSync(temporary).find(value => value.endsWith('.tgz'))
  assert(archive, 'SDK pack must produce a tarball')
  execFileSync('tar', ['-xzf', join(temporary, archive), '-C', temporary])
  symlinkSync(join(sdkRoot, 'packages/sso/node_modules'), join(temporary, 'package/node_modules'), 'dir')
  renameSync(dependency, previous)
  replaced = true
  symlinkSync(join(temporary, 'package'), dependency, 'dir')
  runCookTool('vitest', ['run', 'test/acquisition.test.ts', 'test/yunlefunAuthAdapter.test.ts', 'test/nativeSso.test.ts', 'test/yunlefunAuth.test.ts', 'test/yunlefunSsoConfig.test.ts'])
  runCookTool('eslint', ['apps/web/app/utils/acquisition.ts', 'apps/web/app/plugins/acquisition.client.ts', 'apps/web/app/plugins/yunlefun-auth.client.ts', 'apps/web/app/adapters/yunlefunAuth.ts', 'apps/web/app/utils/nativeSso.ts', 'test/acquisition.test.ts', 'test/yunlefunAuthAdapter.test.ts', 'test/nativeSso.test.ts', 'scripts/qa/verify-acquisition-sdk.mjs'])
  if (process.argv.includes('--build'))
    runCookTool('nuxt', ['build'])
  if (process.argv.includes('--typecheck'))
    runCookTool('nuxt', ['typecheck'])
}
finally {
  if (replaced) {
    rmSync(dependency, { force: true })
    renameSync(previous, dependency)
  }
  rmSync(temporary, { recursive: true, force: true })
}
