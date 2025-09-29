<template>
  <main class="cook-main text-gray-700 text-center dark:text-gray-200" p="t-8 b-$cook-bottom-menu-height">
    <slot />
    <DarkToggle left-4 top-4 absolute />
    <SearchRecipe />
    <TheBottomMenu bottom-0 left-0 right-0 fixed />
  </main>
</template>
