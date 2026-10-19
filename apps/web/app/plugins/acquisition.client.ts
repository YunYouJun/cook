import { captureCookAcquisition } from '~/utils/acquisition'

export default defineNuxtPlugin({
  name: 'cook-acquisition',
  enforce: 'pre',
  setup() {
    captureCookAcquisition()
  },
})
