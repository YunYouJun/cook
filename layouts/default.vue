<template>
  <main class="text-center text-gray-700 dark:text-gray-200" p="x-2 t-8 b-15">
    <slot />
    <DarkToggle absolute right-5 top-5 />
    <TheBottomMenu fixed bottom-0 left-0 right-0 />
  </main>
</template>
