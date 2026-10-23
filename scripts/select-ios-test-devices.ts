import { execFile } from 'node:child_process'
import process from 'node:process'
import { promisify } from 'node:util'
import { selectIOSTestDevices } from './lib/ios-test-devices'

const exec = promisify(execFile)
const { stdout: inventory } = await exec('xcrun', ['simctl', 'list', 'devices', 'available', '-j'])
const { stdout: destinations } = await exec('xcodebuild', ['-showdestinations', '-project', 'apps/ios/Cook.xcodeproj', '-scheme', 'Cook'], { maxBuffer: 4 * 1024 * 1024 })
try {
  const selected = selectIOSTestDevices(JSON.parse(inventory).devices, destinations)
  process.stdout.write(`iphone=${selected.iphone}\nipad=${selected.ipad}\n`)
}
catch (error) {
  process.stderr.write(destinations)
  throw error
}
