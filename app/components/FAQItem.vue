<script lang="ts" setup>
import { Disclosure, DisclosureButton, DisclosurePanel } from '@headlessui/vue'

defineProps<{
  title: string
  defaultOpen?: boolean
}>()
</script>

<template>
  <Disclosure v-slot="{ open }" :default-open="defaultOpen" as="div" class="mt-2">
    <DisclosureButton
      class="text-sm text-blue-900 font-medium px-4 py-2 text-left rounded-lg bg-blue-100 flex w-full justify-between focus:outline-none hover:bg-blue-200 focus-visible:ring focus-visible:ring-blue-500 focus-visible:ring-opacity-75"
    >
      <span>{{ title }}</span>
      <div
        i-ri-arrow-drop-up-line
        :class="open ? 'rotate-180 transform' : ''"
        class="text-blue-500 h-5 w-5"
      />
    </DisclosureButton>
    <DisclosurePanel class="text-sm px-2 pb-2 pt-4">
      <slot />
    </DisclosurePanel>
  </Disclosure>
</template>
