<script lang="ts" setup>
import { Disclosure, DisclosureButton, DisclosurePanel } from '@headlessui/vue'

defineProps<{
  title: string
}>()
</script>

<template>
  <Disclosure v-slot="{ open }" as="div" class="mt-2">
    <DisclosureButton
      class="w-full flex justify-between rounded-lg bg-purple-100 px-4 py-2 text-left text-sm font-medium text-purple-900 hover:bg-purple-200 focus:outline-none focus-visible:ring focus-visible:ring-purple-500 focus-visible:ring-opacity-75"
    >
      <span>{{ title }}</span>
      <div
        i-ri-arrow-drop-up-line
        :class="open ? 'rotate-180 transform' : ''"
        class="h-5 w-5 text-purple-500"
      />
    </DisclosureButton>
    <DisclosurePanel class="px-2 pb-2 pt-4 text-sm text-gray-500">
      <slot />
    </DisclosurePanel>
  </Disclosure>
</template>
