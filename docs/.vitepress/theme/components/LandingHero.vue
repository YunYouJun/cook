<script setup lang="ts">
import { useLandingKitchen } from '../use-landing-kitchen'
import CloudKitchenHero from './CloudKitchenHero.vue'

const { selected, expanded, cooking, served, ingredients, matches, featuredRecipes, toggle, clear, cook, toggleExpanded } = useLandingKitchen()
</script>

<template>
  <CloudKitchenHero
    :selected="selected"
    :ingredients="ingredients"
    :expanded="expanded"
    :recipes="featuredRecipes"
    :match-count="matches.length"
    :cooking="cooking"
    :served="served"
    @select="toggle"
    @expand="toggleExpanded"
    @clear="clear"
    @cook="cook"
  />
</template>
