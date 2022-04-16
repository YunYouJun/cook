<template>
  <span class="animate-stuff" w="4" h="4">
    <slot />
  </span>
</template>

<style>
.animate-stuff {
  visibility: hidden;
  position: absolute;
  z-index: 10;
  border-radius: 50%;
  transition: left .6s linear, top .6s cubic-bezier(0.5, -0.5, 1, 1);
}
</style>
