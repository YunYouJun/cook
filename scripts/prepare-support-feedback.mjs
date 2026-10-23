import { execFileSync } from 'node:child_process'
/** Prepare an isolated cloud-function bundle; never publish the private SDK in this repository. */
import { createHash } from 'node:crypto'
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const VERSION = '0.2.0'
const SHA256 = '2ac5e8b1adec0d6e80116b2e439cde2318d550d16012b9b6dd30ed9c70daaa1c'
const root = fileURLToPath(new URL('..', import.meta.url))
const [output, localSdk] = process.argv.slice(2)
if (!output || !path.isAbsolute(output))
  throw new Error('Usage: node scripts/prepare-support-feedback.mjs /absolute/new-output-directory [/absolute/sdk.tgz]')
const scratch = await mkdtemp(path.join(tmpdir(), 'cook-support-sdk-'))
const name = `yunlefun-support-client-${VERSION}.tgz`
let created = false
try {
  let sdk = localSdk
  if (!sdk) {
    execFileSync('gh', ['release', 'download', `support-client-v${VERSION}`, '--repo', 'YunLeFun/support', '--pattern', name, '--dir', scratch], { stdio: 'inherit' })
    sdk = path.join(scratch, name)
  }
  const bytes = await readFile(sdk)
  if (createHash('sha256').update(bytes).digest('hex') !== SHA256)
    throw new Error('Support SDK integrity mismatch')
  await mkdir(output)
  created = true
  const target = path.join(output, 'cook-feedback')
  await mkdir(path.join(target, 'vendor'), { recursive: true })
  for (const file of ['index.js', 'support.js'])
    await copyFile(path.join(root, 'cloudfunctions/cook-feedback', file), path.join(target, file))
  const pkg = JSON.parse(await readFile(path.join(root, 'cloudfunctions/cook-feedback/package.json'), 'utf8'))
  pkg.dependencies['@yunlefun/support-client'] = `file:vendor/${name}`
  await writeFile(path.join(target, 'package.json'), `${JSON.stringify(pkg, null, 2)}\n`)
  await writeFile(path.join(target, 'vendor', name), bytes)
  await writeFile(path.join(output, 'manifest.json'), `${JSON.stringify({ sdk: VERSION, sha256: SHA256, function: 'cook-feedback', envId: 'yunlefun-8g7ybcxc7345c490' }, null, 2)}\n`)
  console.log(`Prepared cook-feedback with verified Support SDK ${VERSION}: ${output}`)
}
catch (error) {
  if (created)
    await rm(output, { recursive: true, force: true })
  throw error
}
finally {
  await rm(scratch, { recursive: true, force: true })
}
