<template>
  <a-layout class="basic-layout" :class="{ 'basic-layout--fullscreen': route.meta.fullscreen }">
    <!-- 顶部导航栏 -->
    <GlobalHeader v-if="!route.meta.fullscreen" />

    <!-- 主要内容区域 -->
    <a-layout-content
      class="main-content"
      :class="{
        'main-content--home': route.path === '/',
        'main-content--fullscreen': route.meta.fullscreen,
      }"
    >
      <router-view />
    </a-layout-content>

    <!-- 底部版权信息 -->
    <GlobalFooter v-if="!route.meta.fullscreen" />
  </a-layout>
</template>

<script setup lang="ts">
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<style scoped>
.basic-layout {
  min-height: 100vh;
  background: #f7f9fb;
}

.main-content {
  max-width: 1200px;
  width: calc(100% - 48px);
  padding: 24px;
  background: white;
  margin: 32px auto;
  border-radius: 16px;
  min-height: calc(100vh - 220px);
}

.main-content--home,
.main-content--fullscreen {
  width: 100%;
  max-width: none;
  padding: 0;
  margin: 0;
  border-radius: 0;
  background: transparent;
}

.basic-layout--fullscreen {
  height: 100dvh;
  min-height: 0;
}

.main-content--fullscreen {
  min-height: 0;
}

@media (max-width: 640px) {
  .main-content:not(.main-content--home):not(.main-content--fullscreen) {
    width: calc(100% - 24px);
    margin: 16px auto;
    padding: 16px;
  }
}
</style>
