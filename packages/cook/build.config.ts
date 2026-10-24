import { defineBuildConfig } from 'unbuild'

export default defineBuildConfig({
  entries: [
    'src/index',
    'src/utils/recommend',
    'src/utils/custom-recipes',
    'src/cli',
  ],
  clean: true,
  declaration: true,
  rollup: {
    emitCJS: false,
  },
})
