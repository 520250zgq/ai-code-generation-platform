<script setup lang="ts">
import { computed } from 'vue'
import { renderGeneratedResponse } from '@/utils/generatedResponse'
import 'highlight.js/styles/github-dark.css'

const props = defineProps<{
  content: string
}>()

const renderedContent = computed(() => renderGeneratedResponse(props.content))
</script>

<template>
  <div class="markdown-content">
    <!-- eslint-disable-next-line vue/no-v-html -->
    <div class="markdown-body" v-html="renderedContent"></div>
  </div>
</template>

<style scoped>
.markdown-content {
  width: 100%;
  max-width: 400px;
  min-width: 0;
  color: #405b4c;
  font-size: 13px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}

.markdown-body {
  min-width: 0;
}

.markdown-body :deep(:first-child) {
  margin-top: 0;
}

.markdown-body :deep(:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(h1),
.markdown-body :deep(h2),
.markdown-body :deep(h3),
.markdown-body :deep(h4),
.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  margin: 18px 0 8px;
  color: #294938;
  font-weight: 650;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.markdown-body :deep(h1) {
  font-size: 1.65em;
}

.markdown-body :deep(h2) {
  font-size: 1.45em;
}

.markdown-body :deep(h3) {
  font-size: 1.25em;
}

.markdown-body :deep(h4) {
  font-size: 1.1em;
}

.markdown-body :deep(h5),
.markdown-body :deep(h6) {
  font-size: 1em;
}

.markdown-body :deep(p) {
  margin: 0 0 10px;
}

.markdown-body :deep(strong) {
  color: #294938;
  font-weight: 650;
}

.markdown-body :deep(em) {
  color: #526e5d;
}

.markdown-body :deep(ul),
.markdown-body :deep(ol) {
  margin: 6px 0 12px !important;
  padding-left: 1.65em !important;
}

.markdown-body :deep(ul) {
  list-style: disc outside !important;
}

.markdown-body :deep(ol) {
  list-style: decimal outside !important;
}

.markdown-body :deep(li) {
  padding-left: 0.12em;
  margin: 3px 0;
}

.markdown-body :deep(li > ul),
.markdown-body :deep(li > ol) {
  margin: 2px 0 5px !important;
}

.markdown-body :deep(blockquote) {
  margin: 12px 0;
  padding: 8px 12px;
  border-left: 3px solid #a8d4b8;
  border-radius: 0 6px 6px 0;
  background: #f3f8f4;
  color: #668071;
}

.markdown-body :deep(blockquote > :last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(.generated-summary) {
  margin: 0 0 14px;
  padding: 10px 12px;
  border: 1px solid #e5efe8;
  border-radius: 8px;
  background: #f4faf6;
}

.markdown-body :deep(.generated-summary-label) {
  margin-bottom: 4px;
  color: #28815c;
  font-size: 11px;
  font-weight: 650;
}

.markdown-body :deep(.generated-summary p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(code:not(.hljs)) {
  padding: 2px 5px;
  border: 1px solid #e6eee8;
  border-radius: 4px;
  background: #f2f6f3;
  color: #287554;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 0.9em;
  overflow-wrap: anywhere;
}

.markdown-body :deep(.code-block) {
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 100%;
  max-height: 380px;
  min-width: 0;
  margin: 12px 0;
  overflow: hidden;
  border: 1px solid #263648;
  border-radius: 8px;
  background: #0d1117;
}

.markdown-body :deep(.code-language) {
  flex: none;
  padding: 4px 11px;
  border-bottom: 1px solid #263648;
  color: #9aa9b8;
  font-size: 10px;
  line-height: 1.7;
}

.markdown-body :deep(.code-block-header) {
  flex: none;
  border-bottom: 1px solid #263648;
}

.markdown-body :deep(.code-block pre) {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  max-width: 100%;
  max-height: 348px;
  margin: 0;
  padding: 11px 12px;
  overflow: auto;
  tab-size: 2;
  scrollbar-color: #46515e #0d1117;
  scrollbar-width: thin;
}

.markdown-body :deep(.code-block pre code) {
  display: block;
  width: max-content;
  min-width: 100%;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 11px;
  line-height: 1.7;
  white-space: pre;
  overflow-wrap: normal;
}

.markdown-body :deep(table) {
  display: block;
  width: 100%;
  max-width: 100%;
  margin: 12px 0;
  overflow-x: auto;
  border-collapse: collapse;
  border-spacing: 0;
  font-size: 12px;
  white-space: nowrap;
}

.markdown-body :deep(th),
.markdown-body :deep(td) {
  padding: 6px 10px;
  border: 1px solid #dce9df;
  text-align: left;
}

.markdown-body :deep(th) {
  background: #eff6f1;
  color: #355c46;
  font-weight: 600;
}

.markdown-body :deep(tr:nth-child(even) td) {
  background: #f8fbf8;
}

.markdown-body :deep(hr) {
  height: 1px;
  margin: 16px 0;
  border: 0;
  background: #e2ebe4;
}

.markdown-body :deep(img) {
  display: inline-block;
  max-width: 100%;
  height: auto;
  border-radius: 6px;
  vertical-align: middle;
}

.markdown-body :deep(a) {
  color: #25845e;
  text-decoration: underline;
  text-decoration-color: #b4d8c2;
  text-underline-offset: 2px;
  overflow-wrap: anywhere;
}

.markdown-body :deep(a:hover) {
  color: #176644;
}
</style>
