import { defineBuildConfig } from 'unbuild'

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
})
