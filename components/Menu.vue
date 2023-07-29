<template>
  <nav text-xl p="t-6">
    <NuxtLink class="mx-2 icon-btn" to="/" title="首页">
      <div i-ri-home-2-line />
    </NuxtLink>

    <DarkToggle />

    <NuxtLink class="mx-2 icon-btn hover:text-orange-400" to="/help" title="帮助">
      <div i-ri-question-line />
    </NuxtLink>

    <NuxtLink class="mx-2 icon-btn hover:text-blue-400" to="/about" title="关于">
      <div i-ri-information-line />
    </NuxtLink>

    <a class="mx-2 icon-btn hover:text-pink-400" rel="noreferrer" href="https://space.bilibili.com/1579790" target="_blank" title="BiliBili">
      <div i-ri-bilibili-line />
    </a>

    <a class="hover:text-black-400 mx-2 icon-btn" rel="noreferrer" href="https://github.com/YunYouJun/cook" target="_blank" title="GitHub">
      <div i-ri-github-line />
    </a>
  </nav>
</template>
