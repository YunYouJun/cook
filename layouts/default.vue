<template>
  <main class="cook-main text-center text-gray-700 dark:text-gray-200" p="t-8 b-$cook-bottom-menu-height">
    <slot />
    <DarkToggle absolute left-3 top-5 />
    <SearchRecipe />
    <TheBottomMenu fixed bottom-0 left-0 right-0 />
  </main>
</template>
