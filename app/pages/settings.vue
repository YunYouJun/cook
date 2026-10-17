<script lang="ts" setup>
const { isDark, toggleDark } = useDarkMode()

const app = useAppStore()

definePageMeta({
  layout: 'child',
})
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>设置</ion-title>
        <!-- <ion-buttons slot="end">
          <ion-button color="dark">
            <ion-icon slot="icon-only" :icon="ioniconsPersonCircleOutline" />
          </ion-button>
        </ion-buttons> -->
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <ion-list-header>外观</ion-list-header>
      <ion-list :inset="true">
        <ion-item>
          <ion-toggle :checked="isDark" justify="space-between" @ion-change="toggleDark">
            暗色模式
          </ion-toggle>
        </ion-item>
      </ion-list>

      <ion-list-header>做菜视图</ion-list-header>
      <ion-list :inset="true">
        <ion-item>
          <div class="view-settings">
            <RecipeViewSwitch v-model="app.recipeView" />
            <p>经典视图保留紧凑标签和原有筛选；匹配视图展示食材齐全、还差一种、还差两种。</p>
            <p>自动记住上次选择，下次打开继续使用。也可以在做菜页直接切换。</p>
            <p class="view-settings-note">
              视图偏好独立保存，不受下方食材数据开关影响。
            </p>
          </div>
        </ion-item>
      </ion-list>

      <ion-list-header>数据</ion-list-header>
      <ion-list :inset="true">
        <ion-item>
          <ion-toggle v-model:checked="app.settings.keepLocalData" justify="space-between">
            离开网页后保留选中数据
          </ion-toggle>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>

<style scoped>
.view-settings {
  width: 100%;
  padding-bottom: 16px;
  font-size: 14px;
  line-height: 1.7;
}
.view-settings p + p {
  margin-top: 8px;
}
.view-settings-note {
  font-size: 12px;
  color: var(--ion-color-medium-shade);
}
</style>

<style>
/* (Optional) This is added to prevent the flashing that happens when toggling between palettes */
ion-item {
  --transition: none;
}
</style>
