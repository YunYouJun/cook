import { execSync } from 'node:child_process'
import { copyFileSync, mkdirSync, rmSync, statSync } from 'node:fs'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const skillName = 'cook'
const skillDir = resolve(root, 'skills', skillName)
const outputDir = resolve(root, 'dist')
const outputPath = resolve(outputDir, `${skillName}.zip`)

// 需要打包的数据文件（供 Fallback 使用，CLI 不可用时 AI 直接读取）
const dataFiles = [
  'recipe.csv',
  'incompatible-foods.csv',
]

const dataSourceDir = resolve(root, 'app/data')
const dataTargetDir = resolve(skillDir, 'data')

// 1. 复制数据文件到 skills/cook/data/
mkdirSync(dataTargetDir, { recursive: true })
for (const file of dataFiles) {
  copyFileSync(resolve(dataSourceDir, file), resolve(dataTargetDir, file))
}

// 2. 打包（保留目录结构，data/ 作为子目录）
mkdirSync(outputDir, { recursive: true })
execSync(`zip -r "${outputPath}" .`, { cwd: skillDir, stdio: 'ignore' })

// 3. 清理临时复制的数据目录
rmSync(dataTargetDir, { recursive: true, force: true })

const size = (statSync(outputPath).size / 1024).toFixed(1)
console.log(`✅ Packed skills/${skillName}/ → dist/${skillName}.zip (${size} KB)`)
