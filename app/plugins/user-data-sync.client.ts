export default defineNuxtPlugin({
  name: 'cook-user-data-sync',
  dependsOn: ['cook-auth'],
  setup() {
    useUserDataSync().install()
  },
})
