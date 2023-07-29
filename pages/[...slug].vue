<template>
  <main class="markdown-body px-4 text-left">
    <ContentDoc v-slot="{ doc }">
      <h1>{{ doc.title }}</h1>
      <ContentRenderer :value="doc" />
    </ContentDoc>
  </main>
  <BaseFooter />
</template>
