export default defineNuxtPlugin({
  name: 'cook-auth',
  dependsOn: ['cook-acquisition'],
  setup() {
    void useYunlefunAuth().initialize()
  },
})
