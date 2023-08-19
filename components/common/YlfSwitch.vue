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
    class="relative h-6 w-11 inline-flex shrink-0 cursor-pointer border-2 border-transparent rounded-full transition-colors duration-200 ease-in-out focus:outline-none focus-visible:ring-2 focus-visible:ring-white focus-visible:ring-opacity-75"
    @update:model-value="updateModelValue"
  >
    <span class="sr-only">Use setting</span>
    <span
      aria-hidden="true"
      :class="modelValue ? 'translate-x-5' : 'translate-x-0'"
      class="pointer-events-none inline-block h-5 w-5 transform rounded-full bg-white shadow-lg ring-0 transition duration-200 ease-in-out"
    />
  </Switch>
</template>
