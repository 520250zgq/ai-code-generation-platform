<script setup lang="ts">
import { ref, watch } from 'vue'
import defaultAvatar from '@/assets/avatar.jpg'

const props = withDefaults(
  defineProps<{ app: API.AppVO; manageable?: boolean; featured?: boolean; deleting?: boolean }>(),
  { manageable: false, featured: false, deleting: false },
)
defineEmits<{ detail: [app: API.AppVO]; delete: [app: API.AppVO] }>()
const imageFailed = ref(false)
watch(
  () => props.app.cover,
  () => {
    imageFailed.value = false
  },
)
</script>

<template>
  <article class="app-card">
    <RouterLink
      class="card-preview"
      :to="`/app/chat/${app.id}`"
      :aria-label="`打开${app.appName || '未命名应用'}`"
    >
      <img
        v-if="app.cover && !imageFailed"
        :src="app.cover"
        :alt="`${app.appName || '应用'}封面`"
        loading="lazy"
        referrerpolicy="no-referrer"
        @error="imageFailed = true"
      />
      <div v-else class="cover-placeholder">
        <div class="mini-browser" aria-hidden="true">
          <div class="browser-bar"><i /><i /><i /><span /></div>
          <div class="browser-content">
            <div class="browser-symbol">✦</div>
            <div class="browser-line long" />
            <div class="browser-line" />
            <div class="browser-tiles"><span /><span /><span /></div>
          </div>
        </div>
        <span class="placeholder-label">{{
          app.codeGenType ? '开启你的应用' : '让灵感变成现实'
        }}</span>
      </div>
      <span v-if="featured" class="featured-badge">✦ 精选</span>
      <span class="preview-overlay"
        ><span>打开应用 <span aria-hidden="true">↗</span></span></span
      >
    </RouterLink>
    <div class="card-meta">
      <div class="app-info">
        <a-avatar :size="40" :src="app.user?.userAvatar || defaultAvatar" class="creator-avatar">{{
          (app.user?.userName || '创作者').slice(0, 1)
        }}</a-avatar>
        <div class="app-details">
          <div class="title-row">
            <RouterLink
              class="app-title"
              :to="`/app/chat/${app.id}`"
              :title="app.appName || '未命名应用'"
              >{{ app.appName || '未命名应用' }}</RouterLink
            ><span v-if="app.deployKey" class="deployed-badge">已部署</span>
          </div>
          <span class="creator-name" :title="app.user?.userName || '创作者'">{{
            app.user?.userName || '创作者'
          }}</span>
        </div>
      </div>
      <div class="card-actions">
        <button class="detail-button" type="button" @click="$emit('detail', app)">
          查看详情 <span aria-hidden="true">↗</span>
        </button>
        <div v-if="manageable" class="management-actions">
          <RouterLink :to="`/app/edit/${app.id}`" class="edit-button">编辑</RouterLink
          ><a-popconfirm
            title="确定删除这个应用吗？"
            description="删除后将无法恢复，请谨慎操作。"
            ok-text="删除"
            cancel-text="取消"
            :ok-button-props="{ danger: true, loading: deleting }"
            @confirm="$emit('delete', app)"
            ><a-button class="delete-button" type="text" danger size="small" :loading="deleting"
              >删除</a-button
            ></a-popconfirm
          >
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.app-card {
  min-width: 0;
}
.card-preview {
  position: relative;
  display: block;
  overflow: hidden;
  aspect-ratio: 1.7;
  border: 1px solid #eaf0ed;
  border-radius: 15px;
  background: #f4f8f6;
  isolation: isolate;
}
.card-preview > img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: top;
  transition: transform 0.35s;
}
.card-preview:hover > img {
  transform: scale(1.035);
}
.cover-placeholder {
  display: flex;
  height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background:
    radial-gradient(ellipse at 15% 100%, #d7eee3, transparent 75%),
    linear-gradient(135deg, #f2f8f6, #eaf1f4);
}
.mini-browser {
  width: 59%;
  overflow: hidden;
  border: 1px solid #fff;
  border-radius: 8px;
  background: #ffffffb0;
  box-shadow: 0 8px 20px #496d5d0d;
  transform: rotate(-3deg);
}
.browser-bar {
  display: flex;
  height: 15px;
  align-items: center;
  gap: 3px;
  padding: 0 6px;
  border-bottom: 1px solid #e9f1ec;
}
.browser-bar i {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #c9dcd3;
}
.browser-bar > span {
  width: 44%;
  height: 4px;
  margin-left: 5px;
  border-radius: 2px;
  background: #edf3f0;
}
.browser-content {
  padding: 7px 13px 11px;
}
.browser-symbol {
  color: #69af95;
  font-size: 22px;
  line-height: 1.2;
}
.browser-line {
  width: 50%;
  height: 4px;
  margin: 5px 0;
  border-radius: 3px;
  background: #d9e9e1;
}
.browser-line.long {
  width: 75%;
  background: #c6ddd1;
}
.browser-tiles {
  display: flex;
  gap: 6px;
  margin-top: 10px;
}
.browser-tiles span {
  width: 33%;
  height: 24px;
  border-radius: 4px;
  background: #e7f0eb;
}
.browser-tiles span:nth-child(2) {
  background: #dfede6;
}
.placeholder-label {
  color: #8fa89b;
  font-size: 10px;
  letter-spacing: 2px;
}
.featured-badge {
  position: absolute;
  top: 11px;
  left: 11px;
  padding: 3px 9px;
  border: 1px solid #ffffffc9;
  border-radius: 7px;
  background: #ffffffde;
  color: #418c71;
  font-size: 10px;
  font-weight: 500;
  backdrop-filter: blur(5px);
}
.preview-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #19392b29;
  opacity: 0;
  transition: opacity 0.2s;
}
.preview-overlay > span {
  padding: 9px 15px;
  border-radius: 9px;
  background: #fffffff2;
  color: #254a3a;
  font-size: 12px;
  box-shadow: 0 5px 20px #21483015;
}
.card-preview:hover .preview-overlay,
.card-preview:focus-visible .preview-overlay {
  opacity: 1;
}
.card-meta {
  padding: 13px 3px 0;
}
.app-info {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}
.app-details {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}
.title-row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
}
.app-title {
  overflow: hidden;
  color: #2b3a33;
  font-size: 16px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.2s;
}
.app-title:hover {
  color: #2b9d7c;
}
.deployed-badge {
  flex-shrink: 0;
  padding: 2px 6px;
  border-radius: 5px;
  background: #edf8f1;
  color: #6f9c7e;
  font-size: 10px;
}
.creator-name {
  overflow: hidden;
  color: #9ca8a1;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.creator-avatar {
  flex-shrink: 0;
  background: #e5f3eb;
  color: #77a38b;
  font-size: 10px;
}
.card-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 24px;
  margin-top: 10px;
}
.detail-button {
  padding: 0;
  border: 0;
  background: transparent;
  color: #73998a;
  font: inherit;
  font-size: 11px;
  cursor: pointer;
}
.detail-button:hover {
  color: #239472;
}
.detail-button span {
  margin-left: 4px;
}
.management-actions {
  display: flex;
  align-items: center;
  gap: 7px;
}
.edit-button {
  padding: 3px 5px;
  color: #8a9b91;
  font-size: 11px;
}
.edit-button:hover {
  color: #239472;
}
.delete-button {
  height: 24px;
  padding: 0 5px;
  font-size: 11px;
}
@media (prefers-reduced-motion: reduce) {
  .card-preview > img,
  .preview-overlay,
  .app-title {
    transition: none;
  }
}
</style>
