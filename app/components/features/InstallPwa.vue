<script lang="ts" setup>
const app = useAppStore()

function install() {
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
      <button
        class="shadow"
        text="white" bg="green-500" p="x-4 y-2" m="2" inline-flex
        items-center justify-center rounded-md font-bold
        @click="install"
      >
        <div i-ri-install-line mr-1 inline-flex />
        <span inline-flex>安装到桌面</span>
      </button>
    </div>
  </Transition>
</template>
