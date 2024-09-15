<script lang="ts" setup>
import pkg from '~/../package.json'

const commitSha = (import.meta.env.VITE_COMMIT_REF || '').slice(0, 7)
const now = import.meta.env.VITE_APP_BUILD_TIME
const buildDate = (new Date(Number.parseInt(now))).toLocaleDateString()
</script>

<template>
  <div v-if="commitSha && buildDate" mb-2 text-sm>
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
