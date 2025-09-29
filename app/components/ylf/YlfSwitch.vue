<script lang="ts" setup>
import { Switch } from '@headlessui/vue'

defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits(['update:modelValue'])
function updateModelValue(value: boolean) {
  emit('update:modelValue', value)
}
</script>

<template>
  <Switch
    :model-value="modelValue"
    :class="modelValue ? 'bg-blue-600' : 'bg-gray'"
    class="border-2 border-transparent rounded-full inline-flex shrink-0 h-6 w-11 cursor-pointer transition-colors duration-200 ease-in-out relative focus:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-opacity-75"
    @update:model-value="updateModelValue"
  >
    <span class="sr-only">Use setting</span>
    <span
      aria-hidden="true"
      :class="modelValue ? 'translate-x-5' : 'translate-x-0'"
      class="rounded-full bg-white h-5 w-5 inline-block pointer-events-none ring-0 shadow-lg transform transition duration-200 ease-in-out"
    />
  </Switch>
</template>
