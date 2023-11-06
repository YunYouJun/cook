<script setup lang="ts">
import { Dialog, DialogDescription, DialogPanel, DialogTitle, TransitionChild, TransitionRoot } from '@headlessui/vue'

import { db } from '~/utils/db'

const isOpen = ref(false)

function closeModal() {
  isOpen.value = false
}
function openModal() {
  isOpen.value = true
}

const keyword = ref('')
async function getFilterRecipes(keyword: string) {
  return db.recipes.filter((recipe) => {
    return recipe.name.includes(keyword)
  }).toArray()
}
const filteredRecipes = computedAsync(async () => {
  return await getFilterRecipes(keyword.value)
})
</script>

<template>
  <YlfIconButton
    absolute right-3 top-5
    class="icon-btn hover:text-yellow-400 !outline-none"
    text-xl
    title="切换" @click="openModal"
  >
    <div i="ri-search-line" />
  </YlfIconButton>

  <TransitionRoot appear :show="isOpen" as="template">
    <Dialog as="div" class="relative z-10" @close="closeModal">
      <TransitionChild
        as="template"
        enter="duration-300 ease-out"
        enter-from="opacity-0"
        enter-to="opacity-100"
        leave="duration-200 ease-in"
        leave-from="opacity-100"
        leave-to="opacity-0"
      >
        <div class="fixed inset-0" />
      </TransitionChild>

      <div class="fixed inset-0 overflow-y-auto">
        <div
          class="h-full flex justify-center text-center"
        >
          <TransitionChild
            as="template"
            enter="duration-300 ease-out"
            enter-from="opacity-0 scale-95"
            enter-to="opacity-100 scale-100"
            leave="duration-200 ease-in"
            leave-from="opacity-100 scale-100"
            leave-to="opacity-0 scale-95"
          >
            <DialogPanel
              class="h-full max-w-xl w-full transform overflow-hidden bg-white p-4 text-left align-middle shadow-xl transition-all"
              md="mt-4 rounded-2xl"
              overflow="auto"
              flex="~ col"
            >
              <DialogTitle
                as="h3"
                class="flex items-center justify-center text-lg font-medium leading-6 text-gray-900"
              >
                <div relative inline-flex flex="grow">
                  <div
                    i-ri-search-line
                    class="absolute left-3 top-2 cursor-pointer text-gray-400"
                  />
                  <input
                    v-model="keyword"
                    type="text"
                    class="w-full border border-gray-300 rounded-full text-sm focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 placeholder-gray-400"
                    py-2 pl-10 pr-3
                    placeholder="搜索菜谱"
                  >
                  <div
                    v-if="keyword" i-ri-close-line
                    class="absolute right-3 top-2 cursor-pointer text-gray-400"
                    @click="keyword = ''"
                  />
                </div>
                <div ml-2 inline-flex op="70" text-base @click="closeModal">
                  取消
                </div>
              </DialogTitle>
              <div flex="~ col grow" overflow="auto" class="mt-2" text-xs>
                <DishTag v-for="item, i in filteredRecipes" :key="i" :dish="item" />
              </div>
            </DialogPanel>
          </TransitionChild>
        </div>
      </div>
    </Dialog>
  </TransitionRoot>
</template>
