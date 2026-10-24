import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

export const root = fileURLToPath(new URL('../', import.meta.url))
export function runAmountRuntime() {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'cook-amount-audit-'))
  try {
    const files = ['RecipeText', 'RecipePresentation', 'RecipeAmounts', 'RecipeInstructionAmounts', 'RecipeStepUsagePlan', 'RecipeAmountAnnotations']
      .map(name => `apps/ios/CookCore/Sources/CookCore/${name}.swift`)
    const executable = path.join(temp, 'audit')
    execFileSync('swiftc', ['-O', ...files, 'scripts/audit-recipe-amounts.swift', '-o', executable], { cwd: root, stdio: ['ignore', 'pipe', 'inherit'] })
    return JSON.parse(execFileSync(executable, [path.join(root, 'packages/data')], { cwd: root, maxBuffer: 100 * 1024 * 1024 }).toString())
  }
  finally {
    const bytes = fs.readdirSync(temp).reduce((sum, name) => sum + fs.statSync(path.join(temp, name)).size, 0)
    fs.rmSync(temp, { recursive: true, force: true })
    process.stderr.write(`已回收本次 Swift 导出临时目录，${bytes} bytes。\n`)
  }
}
