<script setup lang="ts">
import { formatDateTime } from '@/utils/app'
import CodeGenTypeTag from '@/components/CodeGenTypeTag.vue'

const props = withDefaults(
  defineProps<{
    app: API.AppVO
    showUpdateTime?: boolean
    timePrecision?: 'minute' | 'second'
    creatorFallback?: string
    generationFallback?: string
    promptFallback?: string
    deploymentFallback?: string
    timeFallback?: string
    promptMaxHeight?: number
    promptLabel?: string
  }>(),
  {
    showUpdateTime: false,
    timePrecision: 'minute',
    creatorFallback: '创作者',
    generationFallback: '待生成',
    promptFallback: '暂无描述',
    deploymentFallback: '暂未部署',
    timeFallback: '时间未知',
    promptLabel: '初始提示词',
  },
)

function formatTime(value?: string) {
  return formatDateTime(value, props.timeFallback, props.timePrecision === 'second')
}
</script>

<template>
  <a-descriptions bordered :column="1" size="small">
    <a-descriptions-item label="应用名称">
      {{ app.appName || '未命名应用' }}
    </a-descriptions-item>
    <a-descriptions-item label="应用 ID">
      <span class="breakable-text">{{ app.id }}</span>
    </a-descriptions-item>
    <a-descriptions-item label="创建者">
      {{ app.user?.userName || creatorFallback }}
    </a-descriptions-item>
    <a-descriptions-item label="生成类型">
      <CodeGenTypeTag
        v-if="app.codeGenType"
        :type="app.codeGenType"
        :text="app.codeGenTypeText"
      />
      <span v-else>{{ generationFallback }}</span>
    </a-descriptions-item>
    <a-descriptions-item label="创建时间">{{ formatTime(app.createTime) }}</a-descriptions-item>
    <a-descriptions-item v-if="showUpdateTime" label="更新时间">
      {{ formatTime(app.updateTime) }}
    </a-descriptions-item>
    <a-descriptions-item label="部署时间">
      {{ app.deployedTime ? formatTime(app.deployedTime) : deploymentFallback }}
    </a-descriptions-item>
    <a-descriptions-item :label="promptLabel">
      <p class="prompt" :style="promptMaxHeight ? { maxHeight: `${promptMaxHeight}px` } : undefined">
        {{ app.initPrompt || promptFallback }}
      </p>
    </a-descriptions-item>
  </a-descriptions>
</template>

<style scoped>
.breakable-text,
.prompt {
  overflow-wrap: anywhere;
}

.prompt {
  margin: 0;
  white-space: pre-wrap;
  overflow-y: auto;
}
</style>
