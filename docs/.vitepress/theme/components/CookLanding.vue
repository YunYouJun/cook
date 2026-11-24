<script setup lang="ts">
import { useTemplateRef } from 'vue'
import { useScrollReveal } from '../use-scroll-reveal'
import LandingHeader from './LandingHeader.vue'
import LandingHero from './LandingHero.vue'
import ProductGallery from './ProductGallery.vue'
import ProductServices from './ProductServices.vue'
import WarmStory from './WarmStory.vue'

const landingRoot = useTemplateRef<HTMLElement>('landingRoot')
useScrollReveal(landingRoot)
</script>

<template>
  <div ref="landingRoot" class="cook-landing">
    <LandingHeader />
    <main id="product-main">
      <LandingHero />
      <div class="landing-details">
        <WarmStory />
        <ProductGallery />
        <ProductServices />
      </div>
    </main>
  </div>
</template>
