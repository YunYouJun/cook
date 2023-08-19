<template>
  <div class="ylf-form" flex="~ col">
    <slot />
  </div>
</template>

<style lang="scss">
.ylf-form {
  background-color: var(--ylf-c-bg-alt);

  border-top: 1px solid var(--ylf-c-border);
  border-bottom: 1px solid var(--ylf-c-border);

  margin: 10px 0;

  .ylf-form-item {
    border-bottom: 1px solid var(--ylf-c-border);
    &:last-child {
      border-bottom: none;
    }
  }
}
</style>
