<script lang="ts" setup>
import { useAppStore } from '~/store/app'
const app = useAppStore()

const install = () => {
  const deferredPrompt = app.deferredPrompt
  // Show the install prompt
  deferredPrompt.prompt()
  // Wait for the user to respond to the prompt
  deferredPrompt.userChoice.then((choiceResult: any) => {
    if (choiceResult.outcome === 'accepted')
      // eslint-disable-next-line no-console
      console.log('User accepted the install prompt')
    else
      // eslint-disable-next-line no-console
      console.log('User dismissed the install prompt')
  })
}
</script>

<template>
  <Transition>
    <div v-if="app.deferredPrompt" text="center" m="t-2">
      <button class="shadow" text="white" bg="green-500" p="x-4 y-0" m="2" @click="install">
        安装
      </button>
    </div>
  </Transition>
</template>
