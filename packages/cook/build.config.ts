import fs from 'node:fs'
import path from 'node:path'
import { defineBuildConfig } from 'unbuild'

const RUNTIME_DATA_FILES = [
  'recipe.csv',
  'incompatible-foods.csv',
  'shelf-life.json',
  'pantry.json',
  'video-stats.json',
]

export default defineBuildConfig({
  entries: [
    'src/index',
    'src/utils/recommend',
    'src/utils/custom-recipes',
    'src/utils/personal-catalog',
    'src/utils/reviewed-data',
    'src/cli',
  ],
  clean: true,
  declaration: true,
  rollup: {
    emitCJS: false,
  },
  hooks: {
    'build:done': (ctx) => {
      const sourceDir = path.resolve(ctx.options.rootDir, '../data')
      const targetDir = path.resolve(ctx.options.outDir, 'data')
      fs.mkdirSync(targetDir, { recursive: true })
      for (const file of RUNTIME_DATA_FILES) {
        const src = path.join(sourceDir, file)
        if (fs.existsSync(src))
          fs.copyFileSync(src, path.join(targetDir, file))
      }
    },
  },
})
