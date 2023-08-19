<template>
  <div class="ylf-form" flex="~ col" rounded-md>
    <slot />
  </div>
</template>

<style lang="scss">
.ylf-form {
  background-color: var(--ylf-c-bg-alt);

  border: 1px solid var(--ylf-c-border);

  margin: 10px 0;
  overflow: hidden;

  .ylf-form-item {
    border-bottom: 1px solid var(--ylf-c-border);

    &:last-child {
      border-bottom: none;
    }
  }
}
</style>
