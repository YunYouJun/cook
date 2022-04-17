<script lang="ts" setup>
import { storeToRefs } from 'pinia'
import { useRecipeStore } from '~/stores/recipe'
const rStore = useRecipeStore()
const { strict } = storeToRefs(rStore)
</script>

<template>
  <div class="inline-flex justify-center items-center" m="t-2">
    <span :class="!strict && 'text-orange-600'" font="bold" m="x-1" @click="strict = false">
      模糊匹配
    </span>
    <label m="x-1" class="switch">
      <input v-model="strict" type="checkbox">
      <span class="inline-flex justify-center items-center slider round" />
    </label>
    <span :class="strict && 'text-green-600'" font="bold" m="x-1" @click="strict = true">
      精准匹配
    </span>
  </div>
</template>

<style lang="scss">
.switch {
  position: relative;
  display: inline-block;
  width: 48px;
  height: 28px;

  input {
    opacity: 0;
    width: 0;
    height: 0;
  }
}

.slider {
  @apply bg-orange-600;

  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  -webkit-transition: .4s;
  transition: .4s;
}

$size: 20px;

.slider:before {
  position: absolute;
  content: "";
  height: $size;
  width: $size;
  left: 4px;
  bottom: 4px;
  background-color: white;
  -webkit-transition: .4s;
  transition: .4s;
}

input:checked + .slider {
  @apply bg-green-600;
}

input:checked + .slider:before {
  -webkit-transform: translateX($size);
  -ms-transform: translateX($size);
  transform: translateX($size);
}

/* Rounded sliders */
.slider.round {
  border-radius: 28px;

  &:before {
    border-radius: 50%;
  }
}
</style>
