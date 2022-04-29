<script lang="ts" setup>
const install = () => {
  const deferredPrompt = window.deferredPrompt;
  // Show the install prompt
  deferredPrompt.prompt();
  // Wait for the user to respond to the prompt
  deferredPrompt.userChoice.then((choiceResult: any) => {
    if (choiceResult.outcome === 'accepted') {
      console.log('User accepted the install prompt');
    } else {
      console.log('User dismissed the install prompt');
    }
  });
}
</script>

<template>
  <div text="center" m="t-2">
    <button class="shadow" bg="green-500" p="x-4 y-0" m="2" @click="install">
      安装
    </button>
  </div>
</template>
