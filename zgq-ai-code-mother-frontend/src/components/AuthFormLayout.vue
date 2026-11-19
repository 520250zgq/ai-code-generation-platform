<template>
  <div class="auth-form-layout">
    <h2 class="title">{{ title }}</h2>
    <div class="desc">{{ description }}</div>
    <slot />
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    description?: string
  }>(),
  { description: '不写一行代码，生成完整应用' },
)
</script>

<style scoped>
.auth-form-layout {
  max-width: 360px;
  margin: 0 auto;
}

.title {
  margin-bottom: 16px;
  text-align: center;
}

.desc {
  margin-bottom: 16px;
  color: #bbb;
  text-align: center;
}
</style>
