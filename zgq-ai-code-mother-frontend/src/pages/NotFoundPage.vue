<template>
  <a-result status="404" title="页面不存在" sub-title="链接可能已失效，请返回主页继续创建应用。">
    <template #extra>
      <a-button type="primary" @click="router.push('/')">返回主页</a-button>
    </template>
  </a-result>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>
