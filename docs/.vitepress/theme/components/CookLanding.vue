<script setup lang="ts">
import LandingHeader from './LandingHeader.vue'
import LandingHero from './LandingHero.vue'
import ProductGallery from './ProductGallery.vue'
import ProductServices from './ProductServices.vue'
</script>

<template>
  <div class="cook-landing">
    <LandingHeader />
    <main id="product-main">
      <LandingHero />
      <div class="landing-details">
        <ProductGallery />
        <ProductServices />
      </div>
    </main>
  </div>
</template>
