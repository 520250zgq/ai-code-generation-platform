<script setup lang="ts">
import { computed } from 'vue'
import { getCodeGenTypeMeta } from '@/utils/app'

const props = withDefaults(
  defineProps<{
    /** 后端枚举 value：html / multi_file / vue_project。 */
    type?: string
    /** 后端返回的完整展示文案，优先用于详情；标题等紧凑位置使用组件内置短文案。 */
    text?: string
    /** 紧凑模式只展示简短类型名，适合放在应用标题右侧。 */
    compact?: boolean
  }>(),
  { compact: false },
)

const meta = computed(() => getCodeGenTypeMeta(props.type))
const label = computed(
  () => (props.compact ? meta.value.shortLabel : props.text || meta.value.fullLabel),
)
</script>

<template>
  <a-tag class="code-gen-type-tag" :class="`is-${meta.key}`" :title="meta.description">
    <span class="code-gen-type-dot" aria-hidden="true" />
    <span>{{ label }}</span>
  </a-tag>
</template>

<style scoped>
.code-gen-type-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex-shrink: 0;
  margin: 0;
  padding: 0 7px;
  border: 0;
  border-radius: 6px;
  font-size: 10px;
  font-weight: 500;
  line-height: 19px;
  letter-spacing: 0.2px;
  white-space: nowrap;
}

.code-gen-type-tag.is-html {
  color: #2f7d5b;
  background: #e9f7ef;
}

.code-gen-type-tag.is-multi_file {
  color: #356b9e;
  background: #edf5fd;
}

.code-gen-type-tag.is-vue_project {
  color: #6b57a8;
  background: #f3effd;
}

.code-gen-type-tag.is-unknown {
  color: #7b8781;
  background: #f0f3f1;
}

.code-gen-type-dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.65;
}
</style>
