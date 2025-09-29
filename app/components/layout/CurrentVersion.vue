<script lang="ts" setup>
import pkg from '~/../package.json'

const commitSha = (import.meta.env.VITE_COMMIT_REF || '').slice(0, 7)
const date = import.meta.env.VITE_APP_BUILD_DATE
const buildDate = (new Date(date)).toLocaleDateString()
</script>

<template>
  <div v-if="commitSha && buildDate" text-sm mb-2>
    <span>
      当前版本 v{{ pkg.version }}（{{ buildDate }}）:
    </span>
    <span>
      <a border="b-1 dashed" :href="`https://github.com/YunYouJun/cook/commit/${commitSha}`" target="_blank" alt="Cook | GitHub Commit">
        {{ commitSha }}
      </a>
    </span>
  </div>
</template>
