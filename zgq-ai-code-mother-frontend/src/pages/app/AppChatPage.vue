<template>
  <div id="appChatPage">
    <header class="workspace-header">
      <div class="workspace-heading">
        <RouterLink class="home-link" to="/" aria-label="返回主页" title="返回主页">
          <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="m14 6-6 6 6 6"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </RouterLink>
        <div class="app-symbol" aria-hidden="true">✦</div>
        <div class="app-heading-text">
          <div class="app-title-row">
            <h1>{{ app?.appName || '应用工作台' }}</h1>
            <CodeGenTypeTag
              v-if="app?.codeGenType"
              :type="app.codeGenType"
              :text="app.codeGenTypeText"
              compact
            />
          </div>
          <span>{{ isOwner ? '让想法，一步步成为现实' : '发现灵感，查看应用效果' }}</span>
        </div>
        <a-tag v-if="app && !isOwner" class="readonly-tag">只读</a-tag>
      </div>
      <div class="header-actions">
        <a-button v-if="app" class="details-button" @click="detailsOpen = true">应用详情</a-button>
        <RouterLink v-if="canEdit && app" :to="`/app/edit/${app.id}`">
          <a-button :disabled="generating">编辑信息</a-button>
        </RouterLink>
        <a-button
          v-if="isOwner"
          class="download-button"
          :loading="downloading"
          :disabled="!previewUrl || generating || loading || deploying || !app"
          @click="handleDownload"
        >
          下载代码
        </a-button>
        <a-button
          v-if="isOwner"
          type="primary"
          class="deploy-button"
          :loading="deploying"
          :disabled="!previewUrl || generating || loading || !app"
          @click="handleDeploy"
        >
          <template #icon>
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path
                d="M14 5c2.4-2.4 6-2 6-2s.4 3.6-2 6l-6 6-5-5 7-5Z"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linejoin="round"
              />
              <path
                d="m7 10-4 1 3 3m6 1-1 5-3-3M5 16c-1 0-2 1-2 4 3 0 4-1 4-2"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
              />
              <circle cx="16" cy="7" r="1.4" stroke="currentColor" stroke-width="1.4" />
            </svg>
          </template>
          {{ deploying ? '正在部署' : app?.deployedTime ? '重新部署' : '部署应用' }}
        </a-button>
      </div>
    </header>

    <main v-if="loading" class="workspace-feedback">
      <a-spin size="large" tip="正在打开应用工作台…" />
    </main>
    <main v-else-if="loadError" class="workspace-feedback">
      <a-result status="warning" title="暂时无法打开应用" :sub-title="loadError">
        <template #extra>
          <a-space>
            <a-button type="primary" @click="loadApp">重新加载</a-button>
            <RouterLink to="/"><a-button>返回主页</a-button></RouterLink>
          </a-space>
        </template>
      </a-result>
    </main>
    <main v-else-if="app" class="workspace-body">
      <section class="conversation-panel" aria-label="AI 对话区域">
        <div class="panel-heading">
          <div class="panel-heading-title"><span class="live-dot" /> 与 AI 一起创造</div>
          <span class="session-label">{{ generating ? '正在生成' : '应用对话' }}</span>
        </div>

        <div ref="messageContainer" class="message-list" @scroll="handleMessageScroll">
          <div v-if="historyHasMore" class="history-more">
            <a-button size="small" :loading="historyLoading" @click="loadOlderHistory">
              加载更多历史消息
            </a-button>
          </div>
          <div v-if="!isOwner" class="read-only-notice">
            你正在浏览其他用户的应用。仅创建者可以继续对话和部署。
          </div>
          <div v-if="messages.length === 0" class="conversation-welcome">
            <div class="welcome-mark">✦</div>
            <h2>{{ isOwner ? '你好，我们开始创造吧' : '每个好应用，都始于一个想法' }}</h2>
            <p>
              {{
                isOwner
                  ? '说说你想做的网站，我会将你的想法变成可以访问的页面。'
                  : '在右侧查看生成的网站，或了解这个应用的初始灵感。'
              }}
            </p>
            <div v-if="app.initPrompt" class="initial-prompt">
              <span>最初的灵感</span>
              <p>{{ app.initPrompt }}</p>
            </div>
            <a-button
              v-if="isOwner && app.initPrompt && !previewUrl && !probingPreview"
              class="start-button"
              @click="sendMessage(app.initPrompt)"
            >
              ✦ 根据初始提示词生成
            </a-button>
          </div>

          <article
            v-for="item in messages"
            :key="item.id"
            class="chat-message"
            :class="`message-${item.role}`"
          >
            <img
              v-if="item.role === 'assistant'"
              :src="conversationAvatar"
              class="message-avatar"
              alt=""
              aria-hidden="true"
            />
            <div class="message-main">
              <div class="message-author">{{ item.role === 'user' ? '你' : 'AI 创作助手' }}</div>
              <div class="message-bubble" :class="{ 'is-failed': item.status === 'error' }">
                <MarkdownContent
                  v-if="item.role === 'assistant' && item.content"
                  :content="item.content"
                />
                <p v-else-if="item.content" class="message-text">{{ item.content }}</p>
                <div v-else-if="item.status === 'streaming'" class="thinking-indicator">
                  <span /><span /><span /><em>正在思考你的想法</em>
                </div>
                <p v-else class="message-text">
                  {{ item.status === 'stopped' ? '本次生成已停止。' : '未收到生成内容。' }}
                </p>
                <span v-if="item.status === 'streaming' && item.content" class="typing-cursor" />
              </div>
              <div v-if="item.role === 'assistant'" class="message-status" :class="item.status">
                <template v-if="item.status === 'streaming'">正在生成代码，请稍候…</template>
                <template v-else-if="item.status === 'complete'"
                  >✓ 生成完成，网站效果已更新</template
                >
                <template v-else-if="item.status === 'stopped'">已停止生成，未更新预览</template>
                <template v-else-if="item.status === 'error'">{{ item.error }}</template>
                <a-button
                  v-if="
                    isOwner && (item.status === 'error' || item.status === 'stopped') && item.prompt
                  "
                  type="link"
                  size="small"
                  :disabled="generating || deploying"
                  @click="sendMessage(item.prompt)"
                  >重新生成</a-button
                >
              </div>
            </div>
            <img
              v-if="item.role === 'user'"
              :src="conversationAvatar"
              class="message-avatar"
              alt=""
              aria-hidden="true"
            />
          </article>
          <div ref="messagesEnd" class="messages-end" />
        </div>

        <div class="composer-area">
          <a-alert
            v-if="selectedElement"
            class="selected-element-alert"
            type="success"
            show-icon
            closable
            close-text="移除"
            @close="clearSelectedElement"
          >
            <template #message>
              已选中元素 <strong>{{ describeSelectedElement(selectedElement) }}</strong>
            </template>
            <template #description>
              <span class="selected-element-text">{{
                selectedElement.text || '该元素没有文本内容'
              }}</span>
              <span class="selected-element-selector">{{ selectedElement.selector }}</span>
            </template>
          </a-alert>
          <form
            class="composer"
            :class="{ 'is-disabled': !isOwner }"
            @submit.prevent="sendMessage()"
          >
            <a-textarea
              v-model:value="draft"
              :disabled="!isOwner || generating || deploying"
              :auto-size="{ minRows: 3, maxRows: 7 }"
              :maxlength="10000"
              :placeholder="
                isOwner ? '描述你的想法，或告诉我如何继续完善…' : '此应用仅限创建者继续对话'
              "
              aria-label="输入应用生成提示词"
              @keydown="handleComposerKeydown"
            />
            <div class="composer-toolbar">
              <span class="composer-hint"
                ><span>✦</span>
                {{
                  generating
                    ? '正在将灵感写成代码'
                    : editing
                      ? '已进入编辑模式，点击页面元素并描述修改要求'
                      : 'Enter 发送 · Shift + Enter 换行'
                }}</span
              >
              <div class="composer-actions">
                <a-tooltip :title="editModeHint">
                  <a-button
                    class="edit-mode-button"
                    :class="{ active: editing }"
                    html-type="button"
                    :disabled="!isOwner || !previewUrl || generating || deploying"
                    aria-label="可视化编辑页面元素"
                    :aria-pressed="editing"
                    @click="toggleVisualEditing"
                  >
                    <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path
                        d="m5 3 13.5 7.6-6 1.8-1.8 6L5 3Z"
                        stroke="currentColor"
                        stroke-width="1.6"
                        stroke-linejoin="round"
                      />
                      <path
                        d="m12.8 12.6 4.7 4.7"
                        stroke="currentColor"
                        stroke-width="1.6"
                        stroke-linecap="round"
                      />
                    </svg>
                    <span>{{ editing ? '退出编辑' : '编辑元素' }}</span>
                  </a-button>
                </a-tooltip>
                <a-button v-if="generating" class="stop-button" @click="stopGeneration"
                  >■ 停止</a-button
                >
                <a-button
                  v-else
                  type="primary"
                  shape="circle"
                  class="send-button"
                  html-type="submit"
                  aria-label="发送消息"
                  :disabled="!isOwner || !draft.trim() || deploying"
                >
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path
                      d="M12 18V6m-5 5 5-5 5 5"
                      stroke="currentColor"
                      stroke-width="1.9"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                </a-button>
              </div>
            </div>
          </form>
          <p v-if="isOwner" class="session-note">对话历史会自动保存，刷新后仍可查看。</p>
          <p v-else class="session-note">
            当前为只读访问，{{ canEdit ? '可查看已有历史。' : '不能读取或保存对话历史。' }}
          </p>
        </div>
      </section>

      <section class="preview-panel" aria-label="生成网站预览">
        <div class="preview-toolbar">
          <div class="preview-tabs">
            <span class="active-tab">网页预览</span
            ><span v-if="editing" class="editing-badge">编辑模式</span
            ><span v-if="previewUrl" class="preview-ready">已就绪</span>
          </div>
          <div class="preview-tools">
            <div class="viewport-switch" aria-label="预览尺寸">
              <button
                type="button"
                :class="{ active: viewport === 'desktop' }"
                title="桌面预览"
                aria-label="桌面预览"
                :aria-pressed="viewport === 'desktop'"
                @click="viewport = 'desktop'"
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <rect
                    x="3"
                    y="4"
                    width="18"
                    height="13"
                    rx="2"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <path
                    d="M8 21h8m-4-4v4"
                    stroke="currentColor"
                    stroke-width="1.5"
                    stroke-linecap="round"
                  />
                </svg>
              </button>
              <button
                type="button"
                :class="{ active: viewport === 'mobile' }"
                title="手机预览"
                aria-label="手机预览"
                :aria-pressed="viewport === 'mobile'"
                @click="viewport = 'mobile'"
              >
                <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <rect
                    x="6"
                    y="2"
                    width="12"
                    height="20"
                    rx="2"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <path
                    d="M11 18h2"
                    stroke="currentColor"
                    stroke-width="1.5"
                    stroke-linecap="round"
                  />
                </svg>
              </button>
            </div>
            <a-button
              type="text"
              size="small"
              :disabled="!previewUrl || generating"
              title="刷新预览"
              aria-label="刷新预览"
              @click="refreshPreview"
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M20 7v5h-5M4 17v-5h5"
                  stroke="currentColor"
                  stroke-width="1.6"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <path
                  d="M6.1 7a7 7 0 0 1 11.6-1L20 9M4 15l2.3 3A7 7 0 0 0 18 17"
                  stroke="currentColor"
                  stroke-width="1.6"
                  stroke-linecap="round"
                />
              </svg>
            </a-button>
            <a
              v-if="previewUrl"
              :href="previewUrl"
              target="_blank"
              rel="noopener noreferrer"
              class="open-preview"
              title="在新窗口查看"
              aria-label="在新窗口查看"
            >
              <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M14 3h7v7m0-7L10 14M10 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-5"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </a>
          </div>
        </div>
        <div class="preview-content" :class="{ 'mobile-preview': viewport === 'mobile' }">
          <div v-if="previewUrl" class="preview-browser">
            <div class="browser-address-bar">
              <span /><span /><span />
              <div>{{ app.appName || '我的应用' }}</div>
            </div>
            <iframe
              ref="previewFrame"
              :key="previewUrl"
              :src="previewUrl"
              :title="`${app.appName || '应用'}的网站预览`"
              sandbox="allow-scripts allow-same-origin allow-forms allow-popups allow-modals"
              referrerpolicy="no-referrer"
              @load="handlePreviewLoad"
            />
            <div v-if="editing" class="visual-editing-hint">
              <span class="visual-editing-dot" />
              点击页面中的元素进行选中，再次点击“退出编辑”结束
            </div>
            <div v-if="previewLoading" class="iframe-loading"><a-spin tip="正在加载网站…" /></div>
          </div>
          <div v-else class="preview-placeholder">
            <div
              class="preview-illustration"
              :class="{ 'is-generating': generating }"
              aria-hidden="true"
            >
              <div class="illustration-window">
                <i /><i /><i />
                <div class="illustration-code">&lt; / &gt;</div>
                <span class="illustration-line" /><span class="illustration-line short" />
              </div>
              <span class="illustration-spark">✦</span>
            </div>
            <h2>
              {{
                generating
                  ? '你的想法，正在成形'
                  : probingPreview
                    ? '正在寻找你的作品'
                    : '这里，即将呈现你的创意'
              }}
            </h2>
            <p>
              {{
                generating
                  ? 'AI 正在编写网站代码，全部生成完成后将在这里自动预览。'
                  : probingPreview
                    ? '正在检查应用是否已有可预览的网站…'
                    : attemptedGeneration
                      ? '本次生成尚未完成，请在左侧重新生成。完整结果返回后会自动更新预览。'
                      : previewCheckError
                        ? '暂时无法连接预览服务，请检查后端服务后重试。'
                        : isOwner
                          ? '在左侧描述你想创建的网站，生成完成后即可查看效果。'
                          : '这个应用暂时还没有可预览的网站，请稍后再来查看。'
              }}
            </p>
            <div v-if="generating" class="generation-progress"><span /> 生成中，请稍候</div>
            <a-button
              v-else-if="!probingPreview && !attemptedGeneration"
              @click="probeExistingPreview"
              >检查已有预览</a-button
            >
          </div>
        </div>
        <div class="preview-footer">
          <span class="footer-status"
            ><i :class="{ ready: !!previewUrl }" />
            {{ generating ? '代码生成中' : previewUrl ? '预览已更新' : '等待生成' }}</span
          ><span>由 AI 创造，由你定义</span>
        </div>
      </section>
    </main>

    <a-drawer v-model:open="detailsOpen" title="应用详情" :width="440" class="app-details-drawer">
      <AppDetailDescriptions
        v-if="app"
        :app="app"
        show-update-time
        time-precision="second"
        creator-fallback="未知"
        generation-fallback="未设置"
        prompt-fallback="未设置"
        time-fallback="暂无"
      />
    </a-drawer>

    <a-modal v-model:open="deploymentOpen" title="应用部署成功" :footer="null" :width="500">
      <div class="deployment-result">
        <div class="deployment-check">✓</div>
        <h3>让好想法，被更多人看见</h3>
        <p>应用已发布，你可以通过以下地址访问并分享。</p>
        <a-input
          :value="deployedUrl"
          readonly
          aria-label="应用部署地址"
          @focus="selectDeploymentUrl"
        />
        <a-space class="deployment-actions">
          <a-button @click="copyDeploymentUrl">复制链接</a-button>
          <a-button type="primary" :href="deployedUrl" target="_blank" rel="noopener noreferrer"
            >访问网站 ↗</a-button
          >
        </a-space>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import MarkdownContent from '@/components/MarkdownContent.vue'
import AppDetailDescriptions from '@/components/AppDetailDescriptions.vue'
import CodeGenTypeTag from '@/components/CodeGenTypeTag.vue'
import conversationAvatar from '@/assets/avatar.jpg'
import { deployApp, getAppById, getAppVoById } from '@/api/appController'
import { listChatHistoryByCursor } from '@/api/chatHistoryController'
import { useLoginUserStore } from '@/stores/loginUser'
import { getAppPreviewUrl, getErrorMessage, isSameId, toSafeHttpUrl } from '@/utils/app'
import { downloadAppCode } from '@/utils/appDownload'
import { generateAppCode } from '@/utils/appStream'
import {
  clearVisualEditorSelection,
  composePromptWithElement,
  describeSelectedElement,
  mountVisualEditor,
  type SelectedElementInfo,
} from '@/utils/visualEditor'

type MessageStatus = 'streaming' | 'complete' | 'error' | 'stopped'
type ChatMessage = {
  id: number | string
  role: 'user' | 'assistant'
  content: string
  status?: MessageStatus
  prompt?: string
  error?: string
}

const route = useRoute()
const loginUserStore = useLoginUserStore()
const app = ref<API.AppVO>()
const loading = ref(true)
const loadError = ref('')
const draft = ref('')
const messages = ref<ChatMessage[]>([])
const historyHasMore = ref(false)
const historyLoading = ref(false)
const generating = ref(false)
const deploying = ref(false)
const downloading = ref(false)
const detailsOpen = ref(false)
const deploymentOpen = ref(false)
const deployedUrl = ref('')
const previewUrl = ref('')
const previewLoading = ref(false)
const probingPreview = ref(false)
const previewCheckError = ref(false)
const attemptedGeneration = ref(false)
const viewport = ref<'desktop' | 'mobile'>('desktop')
const previewFrame = ref<HTMLIFrameElement>()
const editing = ref(false)
const selectedElement = ref<SelectedElementInfo>()
const messageContainer = ref<HTMLElement>()
const messagesEnd = ref<HTMLElement>()
const isOwner = computed(() => isSameId(app.value?.userId, loginUserStore.loginUser.id))
const canEdit = computed(() => isOwner.value || loginUserStore.loginUser.userRole === 'admin')
const editModeHint = computed(() => {
  if (!previewUrl.value) return '生成网站后即可选中页面元素进行编辑'
  if (generating.value) return '生成过程中不能编辑页面元素'
  return editing.value ? '退出可视化编辑模式' : '进入编辑模式，点击预览中的元素选中'
})

let pageVersion = 0
let nextMessageId = 0
let streamController: AbortController | undefined
let probeController: AbortController | undefined
let stopVisualEditor: (() => void) | undefined
let activeResponse: ChatMessage | undefined
let stickToBottom = true

// Preserve long IDs as received. A type assertion satisfies generated types without losing digits.
/** 从路由参数读取并校验应用 id，保留字符串形式避免雪花 id 精度丢失。 */
function getRouteId() {
  const value = route.params.id
  return typeof value === 'string' && /^\d+$/.test(value) && !/^0+$/.test(value) ? value : undefined
}

/** 中止当前页面的生成与预览探测请求，并清理进行中的响应引用。 */
function cancelPendingRequests() {
  streamController?.abort()
  probeController?.abort()
  streamController = undefined
  probeController = undefined
  activeResponse = undefined
}

/** 加载应用详情、最新对话历史和已有预览，路由切换时用版本号丢弃过期响应。 */
async function loadApp() {
  const version = ++pageVersion
  cancelPendingRequests()
  app.value = undefined
  loading.value = true
  loadError.value = ''
  messages.value = []
  historyHasMore.value = false
  historyLoading.value = false
  olderHistoryCursor = undefined
  draft.value = ''
  generating.value = false
  deploying.value = false
  downloading.value = false
  previewUrl.value = ''
  probingPreview.value = false
  previewCheckError.value = false
  attemptedGeneration.value = false
  previewLoading.value = false
  // 路由切换或重新加载应用时，退出编辑模式并清空上一轮选中的元素。
  stopVisualEditing()
  selectedElement.value = undefined
  deploymentOpen.value = false
  detailsOpen.value = false
  deployedUrl.value = ''
  stickToBottom = true
  const id = getRouteId()
  if (!id) {
    loadError.value = '应用 ID 无效，请从主页重新打开应用。'
    loading.value = false
    return
  }

  try {
    if (!loginUserStore.loginUser.id) await loginUserStore.fetchLoginUser()
    if (version !== pageVersion) return
    let details: API.AppVO | undefined
    try {
      const response = await getAppVoById({ id: id as unknown as number })
      if (response.data.code !== 0 || !response.data.data) {
        throw new Error(response.data.message || '应用不存在或暂无查看权限')
      }
      details = response.data.data
    } catch (error) {
      if (version !== pageVersion) return
      if (loginUserStore.loginUser.userRole !== 'admin') throw error
      const response = await getAppById({ id: id as unknown as number })
      if (response.data.code !== 0 || !response.data.data) {
        throw new Error(response.data.message || '获取应用详情失败')
      }
      details = response.data.data
    }
    if (version !== pageVersion) return
    app.value = details
    const historyLoaded = await loadLatestHistory(version)
    if (version !== pageVersion) return
    loading.value = false
    await nextTick()
    if (messages.value.length > 0) {
      stickToBottom = true
      await scrollMessages(true)
    }

    // 首次进入自己的应用且没有任何历史时，才用初始化提示词自动发起第一轮对话。
    if (isOwner.value && historyLoaded && messages.value.length === 0 && app.value?.initPrompt) {
      await sendMessage(app.value.initPrompt)
      return
    }

    // 已有至少一轮完整对话（用户 + AI）时回显已生成的网站；访客无法读取历史时保留探测兜底。
    if (!historyLoaded || !canEdit.value || messages.value.length >= 2) {
      await probeExistingPreview()
    }
  } catch (error) {
    if (version !== pageVersion) return
    loadError.value = getErrorMessage(error, '应用加载失败，请稍后重试')
    loading.value = false
  }
}

/** 通过 HEAD 请求探测生成目录是否已有可预览的 index.html。 */
async function probeExistingPreview() {
  if (!app.value || generating.value || probingPreview.value || attemptedGeneration.value) return
  const url = getAppPreviewUrl(app.value)
  if (!url) return
  const version = pageVersion
  probeController?.abort()
  const controller = new AbortController()
  probeController = controller
  probingPreview.value = true
  previewCheckError.value = false
  const timeout = window.setTimeout(() => controller.abort(), 10000)
  try {
    const response = await fetch(url, {
      method: 'HEAD',
      credentials: 'include',
      cache: 'no-store',
      signal: controller.signal,
    })
    if (version !== pageVersion || probeController !== controller || generating.value) return
    if (response.ok && response.headers.get('content-type')?.includes('text/html')) {
      setPreview()
    } else if (response.status !== 404) {
      previewCheckError.value = true
    }
  } catch {
    if (version === pageVersion && probeController === controller && !generating.value) {
      previewCheckError.value = true
    }
  } finally {
    window.clearTimeout(timeout)
    if (version === pageVersion && probeController === controller) {
      probingPreview.value = false
      probeController = undefined
    }
  }
}

/** 根据应用类型和 id 生成预览地址，并追加时间参数强制刷新 iframe。 */
function setPreview() {
  if (!app.value) return
  const url = getAppPreviewUrl(app.value)
  if (!url) return
  const preview = new URL(url, window.location.origin)
  preview.searchParams.set('_t', `${Date.now()}`)
  previewUrl.value = preview.toString()
  previewLoading.value = true
  // 页面已经刷新，之前选中的元素可能不再存在，避免把过期信息带进下一轮提示词。
  selectedElement.value = undefined
}

/** 在非生成状态下重新加载预览，避免覆盖正在写入的生成结果。 */
function refreshPreview() {
  if (previewUrl.value && !generating.value) setPreview()
}

/**
 * 进入可视化编辑模式。
 *
 * <p>必须等 iframe 加载完成后再注入；进入时向同源 iframe 写入悬浮/选中样式和点击采集脚本，
 * 子页面通过 postMessage 回传元素信息，这里只负责保存到页面状态。</p>
 */
function startVisualEditing() {
  if (!previewFrame.value || !previewUrl.value || generating.value) return
  // 先撤销上一轮注入，避免 iframe 刷新后残留旧监听或重复绑定 message 事件。
  stopVisualEditing()
  selectedElement.value = undefined
  try {
    stopVisualEditor = mountVisualEditor(previewFrame.value, (element) => {
      selectedElement.value = element
    })
    editing.value = true
  } catch (error) {
    stopVisualEditor = undefined
    editing.value = false
    message.warning(getErrorMessage(error, '无法开启可视化编辑模式'))
  }
}

/** 退出可视化编辑模式，并移除 iframe 内的选择脚本和父页面监听。 */
function stopVisualEditing() {
  stopVisualEditor?.()
  stopVisualEditor = undefined
  editing.value = false
}

/** 切换编辑模式：按钮在发送按钮左侧，生成过程中不可用。 */
function toggleVisualEditing() {
  if (editing.value) {
    stopVisualEditing()
  } else {
    startVisualEditing()
  }
}

/** 主动移除选中的元素，同时清除 iframe 中的选中边框；仍保持编辑模式以便重新选择。 */
function clearSelectedElement() {
  clearVisualEditorSelection(previewFrame.value)
  selectedElement.value = undefined
}

/** iframe 加载或刷新完成后，如果仍在编辑模式，需要重新注入选择脚本。 */
function handlePreviewLoad() {
  previewLoading.value = false
  if (editing.value) startVisualEditing()
}

/** 记录用户是否停留在消息列表底部，用于决定新消息是否自动滚动。 */
function handleMessageScroll() {
  const el = messageContainer.value
  if (el) stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 100
}

/** 把后端对话记录按创建时间升序转换为聊天消息，并标记历史中的失败回复。 */
function mapHistoryRecords(records: API.ChatHistory[] = []): ChatMessage[] {
  let lastUserPrompt = ''
  return records
    .filter((record) => record.messageType === 'user' || record.messageType === 'ai')
    .sort((left, right) => {
      const timeCompare = (left.createTime ?? '').localeCompare(right.createTime ?? '')
      if (timeCompare !== 0) return timeCompare
      return String(left.id ?? '').localeCompare(String(right.id ?? ''))
    })
    .map((record, index) => {
      const content = record.message ?? ''
      const isAi = record.messageType === 'ai'
      const isError = isAi && content.startsWith('AI 回复失败：')
      if (!isAi) lastUserPrompt = content
      return {
        id: `history-${record.id ?? `${Date.now()}-${index}`}`,
        role: (record.messageType === 'user' ? 'user' : 'assistant') as ChatMessage['role'],
        content,
        status: (isAi ? (isError ? 'error' : 'complete') : undefined) as ChatMessage['status'],
        prompt: isError ? lastUserPrompt : undefined,
        error: isError ? '本次生成失败' : undefined,
      }
    })
}

/** 请求一页游标历史，游标为空时读取最新消息。 */
async function fetchHistoryPage(cursor?: { createTime: string; id: string }) {
  if (!app.value?.id || !canEdit.value || historyLoading.value) return undefined
  const version = pageVersion
  historyLoading.value = true
  try {
    const response = await listChatHistoryByCursor({
      appId: String(app.value.id),
      cursorCreateTime: cursor?.createTime,
      cursorId: cursor?.id,
      pageSize: 10,
    })
    if (version !== pageVersion) return undefined
    const result = response.data.data
    if (response.data.code !== 0 || !result) {
      throw new Error(response.data.message || '加载对话历史失败')
    }
    return result
  } finally {
    if (version === pageVersion) historyLoading.value = false
  }
}

/** 首次进入应用时加载最新一页历史，并保存更早消息的游标。 */
async function loadLatestHistory(version: number): Promise<boolean> {
  if (!canEdit.value) return false
  try {
    const result = await fetchHistoryPage()
    if (!result || version !== pageVersion) return false
    messages.value = mapHistoryRecords(result.records)
    const hasNextPage = !!result.hasMore && !!result.nextCursorCreateTime && !!result.nextCursorId
    historyHasMore.value = hasNextPage
    if (hasNextPage) {
      olderHistoryCursor = {
        createTime: result.nextCursorCreateTime!,
        id: result.nextCursorId!,
      }
    } else {
      olderHistoryCursor = undefined
    }
    return true
  } catch (error) {
    if (version === pageVersion) {
      historyHasMore.value = false
      message.warning(getErrorMessage(error, '对话历史暂时无法加载'))
    }
    return false
  }
}

let olderHistoryCursor: { createTime: string; id: string } | undefined

/** 按游标加载更早的历史，并保持用户当前滚动位置不跳动。 */
async function loadOlderHistory() {
  if (!historyHasMore.value || !olderHistoryCursor || !messageContainer.value) return
  const previousHeight = messageContainer.value.scrollHeight
  const previousTop = messageContainer.value.scrollTop
  try {
    const result = await fetchHistoryPage(olderHistoryCursor)
    if (!result) return
    const olderMessages = mapHistoryRecords(result.records)
    messages.value = [...olderMessages, ...messages.value]
    const hasNextPage = !!result.hasMore && !!result.nextCursorCreateTime && !!result.nextCursorId
    historyHasMore.value = hasNextPage
    if (hasNextPage) {
      olderHistoryCursor = {
        createTime: result.nextCursorCreateTime!,
        id: result.nextCursorId!,
      }
    } else {
      olderHistoryCursor = undefined
    }
    await nextTick()
    const container = messageContainer.value
    if (container) container.scrollTop = previousTop + container.scrollHeight - previousHeight
  } catch (error) {
    message.warning(getErrorMessage(error, '更早的对话暂时无法加载'))
  }
}

/** 在自动滚动开启时把消息列表滚动到底部。 */
async function scrollMessages(force = false) {
  await nextTick()
  if (force || stickToBottom) {
    messagesEnd.value?.scrollIntoView({ block: 'end', behavior: 'auto' })
  }
}

/** 处理输入框快捷键：Enter 发送，Shift + Enter 换行。 */
function handleComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void sendMessage()
  }
}

/** 追加本轮用户和 AI 消息，读取 SSE 流并在完成、失败或停止时更新状态。 */
async function sendMessage(value?: string) {
  const prompt = (value ?? draft.value).trim()
  if (!prompt || !app.value?.id || !isOwner.value || generating.value || deploying.value) return
  if (prompt.length > 10000) {
    message.warning('提示词不能超过 10000 个字符')
    return
  }
  // 把选中的页面元素信息合并进本轮提示词；没有选中元素时保持用户输入不变。
  const messageToSend = composePromptWithElement(prompt, selectedElement.value)
  const version = pageVersion
  const appId = app.value.id
  probeController?.abort()
  probeController = undefined
  probingPreview.value = false
  previewUrl.value = ''
  previewLoading.value = false
  // 发送后清除选中元素并退出编辑模式，其他生成流程与之前完全一致。
  stopVisualEditing()
  selectedElement.value = undefined
  generating.value = true
  attemptedGeneration.value = true
  draft.value = ''
  stickToBottom = true
  messages.value.push({ id: ++nextMessageId, role: 'user', content: messageToSend })
  messages.value.push({
    id: ++nextMessageId,
    role: 'assistant',
    content: '',
    status: 'streaming',
    prompt: messageToSend,
  })
  const response = messages.value[messages.value.length - 1]!
  activeResponse = response
  const controller = new AbortController()
  streamController = controller
  void scrollMessages(true)
  try {
    await generateAppCode({
      appId,
      message: messageToSend,
      signal: controller.signal,
      onChunk: (chunk) => {
        if (version !== pageVersion || streamController !== controller) return
        response.content += chunk
        void scrollMessages()
      },
    })
    if (version !== pageVersion || streamController !== controller || controller.signal.aborted)
      return
    response.status = 'complete'
    setPreview()
    void scrollMessages()
  } catch (error) {
    if (version !== pageVersion || streamController !== controller) return
    if (controller.signal.aborted) {
      response.status = 'stopped'
    } else {
      response.status = 'error'
      response.error = getErrorMessage(error, '生成失败，请重试')
      message.error(response.error)
    }
  } finally {
    if (version === pageVersion && streamController === controller) {
      generating.value = false
      streamController = undefined
      activeResponse = undefined
    }
  }
}

/** 主动中止当前生成请求，并把最后一条 AI 消息标记为已停止。 */
function stopGeneration() {
  if (!generating.value) return
  streamController?.abort()
  streamController = undefined
  if (activeResponse) activeResponse.status = 'stopped'
  activeResponse = undefined
  generating.value = false
}

/** 部署当前应用，成功后展示可复制和可访问的部署地址。 */
async function handleDeploy() {
  if (!isOwner.value || !app.value?.id || !previewUrl.value || generating.value || deploying.value)
    return
  const version = pageVersion
  deploying.value = true
  try {
    const response = await deployApp({ appId: app.value.id })
    if (version !== pageVersion) return
    if (response.data.code !== 0 || !response.data.data) {
      throw new Error(response.data.message || '部署失败，请稍后重试')
    }
    const url = toSafeHttpUrl(response.data.data)
    if (!url) throw new Error('服务端返回的部署地址无效，请联系管理员')
    deployedUrl.value = url
    deploymentOpen.value = true
    // Use the server's deployment timestamp when available.
    try {
      const updated = await getAppVoById({ id: app.value.id })
      if (version === pageVersion && updated.data.code === 0 && updated.data.data) {
        app.value = updated.data.data
      }
    } catch {
      // Deployment has succeeded; a detail refresh failure must not report it as failed.
    }
  } catch (error) {
    if (version === pageVersion) message.error(getErrorMessage(error, '部署失败，请稍后重试'))
  } finally {
    if (version === pageVersion) deploying.value = false
  }
}

/** 下载当前应用的源代码 ZIP，服务端 JSON 错误由下载工具转换为可读提示。 */
async function handleDownload() {
  if (
    !isOwner.value ||
    !app.value?.id ||
    !previewUrl.value ||
    generating.value ||
    deploying.value ||
    downloading.value
  )
    return
  const version = pageVersion
  downloading.value = true
  try {
    await downloadAppCode(String(app.value.id), `${app.value.appName || 'app'}_${app.value.id}.zip`)
    if (version === pageVersion) message.success('代码下载已开始')
  } catch (error) {
    if (version === pageVersion) message.error(getErrorMessage(error, '下载代码失败，请稍后重试'))
  } finally {
    if (version === pageVersion) downloading.value = false
  }
}

/** 聚焦部署地址输入框时自动全选，便于手动复制。 */
function selectDeploymentUrl(event: FocusEvent) {
  ;(event.target as HTMLInputElement)?.select()
}

/** 复制部署地址；剪贴板不可用时提示用户手动复制。 */
async function copyDeploymentUrl() {
  try {
    await navigator.clipboard.writeText(deployedUrl.value)
    message.success('链接已复制')
  } catch {
    message.info('请选中上方地址手动复制')
  }
}

watch(() => route.params.id, loadApp, { immediate: true })
watch(
  () => loginUserStore.loginUser.id,
  () => {
    if (generating.value && !isOwner.value) stopGeneration()
  },
)

onBeforeUnmount(() => {
  ++pageVersion
  cancelPendingRequests()
  stopVisualEditing()
})
</script>

<style scoped>
#appChatPage {
  --studio-ink: #172b29;
  --studio-muted: #89928f;
  --studio-accent: #16b99a;
  height: 100dvh;
  min-height: 560px;
  display: flex;
  flex-direction: column;
  color: var(--studio-ink);
  background: #f7faf9;
}

.workspace-header {
  height: 78px;
  flex-shrink: 0;
  padding: 0 28px 0 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  background: #fff;
  border-bottom: 1px solid #e9eeec;
}

.workspace-heading,
.header-actions,
.preview-tools,
.panel-heading-title,
.preview-tabs {
  display: flex;
  align-items: center;
}

.workspace-heading {
  gap: 14px;
  min-width: 0;
}
.header-actions {
  gap: 10px;
  flex-shrink: 0;
}
.home-link {
  color: #71817b;
  display: flex;
  padding: 7px;
  border-radius: 8px;
}
.home-link:hover {
  background: #f0f7f4;
  color: var(--studio-accent);
}
.home-link svg {
  width: 22px;
  height: 22px;
}
.app-symbol {
  width: 40px;
  height: 40px;
  border-radius: 13px;
  display: grid;
  place-items: center;
  color: #133f35;
  font-size: 27px;
  background: #c7f7e7;
  flex-shrink: 0;
}
.app-heading-text {
  min-width: 0;
}
.app-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  margin-bottom: 2px;
}
.app-heading-text h1 {
  font-size: 16px;
  font-weight: 650;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 400px;
}
.app-title-row :deep(.code-gen-type-tag) {
  flex-shrink: 0;
}
.app-heading-text > span {
  font-size: 11px;
  color: #8c9691;
  letter-spacing: 0.6px;
}
.readonly-tag {
  margin: 0;
  color: #7c8b84;
  border: 0;
  background: #eff3f1;
}
.header-actions :deep(.ant-btn) {
  height: 35px;
  border-radius: 8px;
  font-size: 12px;
}
.header-actions .download-button {
  color: #375e4c;
  border-color: #dbe8e1;
  background: #fbfdfb;
}
.header-actions .download-button:disabled {
  color: #a4b0aa;
  border-color: #e2e8e5;
  background: #f7faf8;
}
.header-actions .deploy-button {
  background: #173d33;
  border-color: #173d33;
  padding: 0 17px;
  box-shadow: none;
}
.header-actions .deploy-button:disabled {
  background: #eef2f0;
  border-color: #e2e8e5;
  color: #a4b0aa;
}
.deploy-button svg {
  width: 16px;
  height: 16px;
  vertical-align: -3px;
  margin-right: 5px;
}
.workspace-feedback {
  display: grid;
  flex: 1;
  place-items: center;
}
.workspace-body {
  display: flex;
  flex: 1;
  min-height: 0;
  padding: 16px;
  gap: 16px;
}
.conversation-panel {
  width: 36%;
  min-width: 350px;
  max-width: 560px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #fff;
  border: 1px solid #e7ece9;
  border-radius: 14px;
  overflow: hidden;
}
.panel-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 18px 22px;
  border-bottom: 1px solid #f0f3f1;
  flex-shrink: 0;
}
.panel-heading-title {
  font-size: 12px;
  font-weight: 600;
  gap: 8px;
}
.live-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #24c39d;
  box-shadow: 0 0 0 4px #eafaf3;
}
.session-label {
  font-size: 10px;
  color: #a0aaa4;
}
.message-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 20px 12px;
  scroll-behavior: auto;
  scrollbar-width: thin;
  scrollbar-color: #dce6e0 transparent;
}
.history-more {
  display: flex;
  justify-content: center;
  margin: 0 0 18px;
}
.read-only-notice {
  padding: 12px 14px;
  border-radius: 8px;
  font-size: 12px;
  color: #6b7c72;
  background: #f2f8f4;
  margin-bottom: 20px;
  line-height: 1.7;
}
.conversation-welcome {
  padding: 30px 8px 12px;
}
.welcome-mark {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  border-radius: 15px;
  background: #ecfaf3;
  color: #26a982;
  font-size: 28px;
  margin-bottom: 20px;
}
.conversation-welcome h2 {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 10px;
}
.conversation-welcome > p {
  color: #91a095;
  line-height: 1.9;
  font-size: 12px;
  margin: 0 0 22px;
}
.initial-prompt {
  padding: 16px;
  border: 1px solid #e9efea;
  border-radius: 10px;
  background: #fbfdfb;
}
.initial-prompt > span {
  color: #9eaaa3;
  font-size: 10px;
}
.initial-prompt > p {
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 6px 0 0;
  color: #526a5e;
}
.start-button {
  margin-top: 18px;
  font-size: 12px;
  color: #228b6b;
  border-color: #cae8d8;
  border-radius: 7px;
}
.chat-message {
  display: flex;
  gap: 10px;
  margin-bottom: 26px;
}
.message-main {
  min-width: 0;
  max-width: 100%;
}
.message-user {
  justify-content: flex-end;
}
.message-user .message-main {
  width: calc(100% - 37px);
  max-width: calc(90% - 37px);
}
.message-author {
  color: #8b9991;
  font-size: 10px;
  margin: 2px 0 8px;
}
.message-user .message-author {
  text-align: right;
}
.message-avatar {
  flex-shrink: 0;
  width: 27px;
  height: 27px;
  display: block;
  object-fit: cover;
  border-radius: 9px;
}
.message-assistant .message-main {
  width: calc(100% - 37px);
}
.message-bubble {
  position: relative;
  font-size: 12px;
  line-height: 1.9;
}
.message-user .message-bubble {
  background: #edf6f0;
  padding: 13px 17px;
  border-radius: 12px 3px 12px 12px;
  color: #375e4c;
}
.message-text {
  margin: 0 0 10px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.message-text:last-child {
  margin-bottom: 0;
}
.message-status {
  margin-top: 8px;
  font-size: 10px;
  color: #a1aaa5;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.message-status.complete {
  color: #5d9d80;
}
.message-status.error {
  color: #bf6f6c;
}
.message-status :deep(.ant-btn) {
  font-size: 11px;
  height: auto;
  padding: 0 6px;
}
.thinking-indicator {
  display: flex;
  align-items: center;
  height: 30px;
  gap: 4px;
}
.thinking-indicator > span {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #48aa87;
  animation: thinking 1.2s ease-in-out infinite;
}
.thinking-indicator > span:nth-child(2) {
  animation-delay: 0.15s;
}
.thinking-indicator > span:nth-child(3) {
  animation-delay: 0.3s;
}
.thinking-indicator em {
  font-style: normal;
  font-size: 11px;
  color: #91a296;
  margin-left: 6px;
}
.typing-cursor {
  display: inline-block;
  width: 5px;
  height: 13px;
  background: #41aa88;
  animation: thinking 1s infinite;
  vertical-align: middle;
}
.messages-end {
  height: 1px;
}
.composer-area {
  flex-shrink: 0;
  padding: 12px 16px 8px;
}
.selected-element-alert {
  margin-bottom: 9px;
  padding: 8px 12px;
  border: 1px solid #cfeadd;
  border-radius: 10px;
  background: #f3fbf6;
}
.selected-element-alert :deep(.ant-alert-message) {
  font-size: 11px;
  line-height: 1.6;
  color: #2f5a48;
}
.selected-element-alert :deep(.ant-alert-message strong) {
  font-family: monospace;
  font-weight: 600;
  color: #16795f;
}
.selected-element-alert :deep(.ant-alert-description) {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 10px;
  margin-top: 2px;
}
.selected-element-alert :deep(.ant-alert-close-icon) {
  display: flex;
  align-items: center;
  align-self: center;
  font-size: 10px;
  color: #5e8c78;
}
.selected-element-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #6b8578;
  font-size: 10px;
  max-width: 100%;
}
.selected-element-selector {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #94a89b;
  font-family: monospace;
  font-size: 9px;
}
.composer {
  border: 1px solid #dce8e0;
  border-radius: 12px;
  background: #fcfefc;
  padding: 13px 14px 10px;
  box-shadow: 0 2px 10px #204f3306;
  transition: border-color 0.2s;
}
.composer:focus-within {
  border-color: #77c7a8;
  box-shadow: 0 0 0 3px #39ba8a0a;
}
.composer.is-disabled {
  background: #f7f9f7;
}
.composer :deep(.ant-input) {
  border: 0;
  box-shadow: none;
  background: transparent;
  padding: 0;
  resize: none;
  font-size: 12px;
  line-height: 1.8;
  color: #375544;
}
.composer :deep(.ant-input::placeholder) {
  color: #a3afa7;
}
.composer-toolbar {
  margin-top: 10px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 7px;
}
.composer-hint {
  color: #a3afa7;
  font-size: 9px;
}
.composer-hint > span {
  color: #5ab98c;
  margin-right: 5px;
}
.composer-actions {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-shrink: 0;
}
.edit-mode-button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 11px;
  border-radius: 8px;
  border-color: #d8e6dc;
  background: #fbfdfb;
  color: #4c7663;
  font-size: 11px;
  box-shadow: none;
}
.edit-mode-button svg {
  width: 15px;
  height: 15px;
  display: block;
}
.edit-mode-button:hover:not(:disabled) {
  color: #16795f;
  border-color: #a8dcc5;
}
.edit-mode-button.active,
.edit-mode-button.active:hover {
  color: #f3fff9;
  background: #1e684c;
  border-color: #1e684c;
}
.edit-mode-button:disabled {
  color: #a9b6ad;
  border-color: #e4ebe6;
  background: #f7faf8;
}
.send-button {
  background: #1e684c;
  border-color: #1e684c;
  box-shadow: none;
  min-width: 32px;
  width: 32px;
  height: 32px;
}
.send-button:disabled {
  background: #e2ebe5;
  border-color: #e2ebe5;
  color: #a5b6aa;
}
.send-button svg {
  width: 20px;
  height: 20px;
  display: block;
  margin: auto;
}
.stop-button {
  font-size: 11px;
  color: #7b8e80;
  border-color: #d8e6dc;
  border-radius: 7px;
}
.session-note {
  color: #afb8b1;
  font-size: 9px;
  text-align: center;
  margin: 9px 0 0;
}
.preview-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid #e6ece8;
  border-radius: 14px;
  overflow: hidden;
  background: #f0f5f2;
}
.preview-toolbar {
  height: 53px;
  flex-shrink: 0;
  padding: 0 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
  border-bottom: 1px solid #e7eee9;
}
.preview-tabs {
  height: 100%;
  gap: 13px;
}
.active-tab {
  display: flex;
  align-items: center;
  height: 100%;
  border-bottom: 2px solid #4cb58b;
  font-size: 12px;
  font-weight: 600;
}
.preview-ready {
  color: #6fa188;
  background: #f0f8f3;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 9px;
}
.editing-badge {
  color: #0f7a62;
  background: #def7ec;
  border: 1px solid #b7ecd8;
  border-radius: 4px;
  padding: 1px 6px;
  font-size: 9px;
  letter-spacing: 0.2px;
}
.preview-tools {
  gap: 9px;
}
.preview-tools svg {
  display: block;
  width: 15px;
  height: 15px;
}
.preview-tools :deep(.ant-btn) {
  height: 27px;
  width: 27px;
  padding: 5px;
  color: #81928a;
}
.preview-tools :deep(.ant-btn:disabled) {
  color: #c6cfc9;
}
.viewport-switch {
  display: flex;
  gap: 3px;
  background: #f4f7f5;
  border: 1px solid #edf1ee;
  padding: 3px;
  border-radius: 7px;
}
.viewport-switch button {
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #9ba9a0;
  padding: 4px 7px;
  cursor: pointer;
}
.viewport-switch button.active {
  color: #4d795f;
  background: #fff;
  box-shadow: 0 1px 3px #183d2310;
}
.open-preview {
  padding: 5px;
  color: #81928a;
}
.open-preview:hover {
  color: #2a906a;
}
.preview-content {
  position: relative;
  flex: 1;
  min-height: 0;
  padding: 20px;
  display: flex;
  justify-content: center;
  align-items: stretch;
  background-image: radial-gradient(#cddbd1 0.7px, transparent 0.7px);
  background-size: 16px 16px;
}
.preview-browser {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  width: 100%;
  background: #fff;
  border: 1px solid #dee8e1;
  border-radius: 10px;
  overflow: hidden;
  box-shadow: 0 6px 24px #17402706;
}
.browser-address-bar {
  height: 29px;
  display: flex;
  align-items: center;
  gap: 4px;
  background: #fcfdfc;
  border-bottom: 1px solid #edf1ed;
  padding: 0 11px;
  flex-shrink: 0;
}
.browser-address-bar > span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #dde5de;
}
.browser-address-bar > div {
  margin: 0 auto;
  max-width: 65%;
  color: #a0aca3;
  font-size: 9px;
  text-overflow: ellipsis;
  white-space: nowrap;
  overflow: hidden;
}
.preview-browser iframe {
  width: 100%;
  flex: 1;
  border: 0;
  background: #fff;
  min-height: 0;
}
.iframe-loading {
  position: absolute;
  inset: 29px 0 0;
  background: #ffffffec;
  display: grid;
  place-items: center;
}
.visual-editing-hint {
  position: absolute;
  top: 41px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 14px;
  border-radius: 999px;
  background: #102c25e6;
  color: #eafff7;
  font-size: 10px;
  letter-spacing: 0.2px;
  white-space: nowrap;
  box-shadow: 0 6px 18px #0d2a211f;
  pointer-events: none;
}
.visual-editing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #39d6a8;
  box-shadow: 0 0 0 3px #39d6a833;
}
.mobile-preview .preview-browser {
  max-width: 390px;
}
.preview-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 35px;
  max-width: 420px;
}
.preview-illustration {
  position: relative;
  margin-bottom: 34px;
  width: 154px;
  height: 116px;
}
.illustration-window {
  width: 145px;
  height: 108px;
  border: 1px solid #c7e2d1;
  background: linear-gradient(145deg, #fff, #effaf2);
  border-radius: 13px;
  box-shadow: 0 14px 30px #38573d0d;
  transform: rotate(-5deg);
  position: relative;
  padding: 12px;
  text-align: left;
}
.illustration-window > i {
  width: 4px;
  height: 4px;
  background: #cee3d4;
  border-radius: 50%;
  display: inline-block;
  margin-right: 3px;
  vertical-align: top;
}
.illustration-code {
  color: #4fba8a;
  font-size: 25px;
  letter-spacing: 2px;
  font-family: monospace;
  text-align: center;
  margin-top: -4px;
}
.illustration-line {
  height: 4px;
  border-radius: 2px;
  display: block;
  margin: 6px auto;
  background: #dbeee1;
  width: 62px;
}
.illustration-line.short {
  width: 39px;
}
.illustration-spark {
  width: 39px;
  height: 39px;
  background: #dcf4e6;
  border: 3px solid #f0f6f1;
  border-radius: 12px;
  position: absolute;
  bottom: -1px;
  right: -5px;
  display: grid;
  place-items: center;
  color: #3cb17e;
  font-size: 23px;
  transform: rotate(8deg);
}
.is-generating .illustration-spark {
  animation: floating 2s ease-in-out infinite;
}
.preview-placeholder h2 {
  font-size: 19px;
  font-weight: 550;
  letter-spacing: 0.4px;
  color: #486554;
  margin: 0 0 13px;
}
.preview-placeholder > p {
  color: #94a59a;
  font-size: 12px;
  line-height: 1.9;
  max-width: 290px;
  margin: 0 0 21px;
}
.preview-placeholder :deep(.ant-btn) {
  font-size: 11px;
  border-color: #d8e6dc;
  color: #7d9785;
  border-radius: 7px;
  background: #ffffffad;
}
.generation-progress {
  font-size: 11px;
  color: #5e9f7b;
  background: #e5f3e9;
  padding: 6px 13px;
  border-radius: 20px;
  display: flex;
  gap: 7px;
  align-items: center;
}
.generation-progress > span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #57b385;
  animation: thinking 1s infinite;
}
.preview-footer {
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  font-size: 9px;
  color: #a5b0a8;
  background: #fafcfa;
  border-top: 1px solid #e7eee8;
  flex-shrink: 0;
}
.footer-status {
  display: flex;
  align-items: center;
  gap: 5px;
}
.footer-status i {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #becbc1;
}
.footer-status i.ready {
  background: #58b88b;
}
.deployment-result {
  text-align: center;
  padding: 16px 6px 8px;
}
.deployment-check {
  width: 54px;
  height: 54px;
  border-radius: 50%;
  margin: 0 auto 18px;
  display: grid;
  place-items: center;
  background: #e8f8ec;
  font-size: 28px;
  color: #3a9f69;
}
.deployment-result h3 {
  color: #244335;
  font-weight: 600;
  margin: 0 0 9px;
  font-size: 18px;
}
.deployment-result > p {
  color: #97a398;
  font-size: 12px;
  margin-bottom: 22px;
}
.deployment-result :deep(.ant-input) {
  background: #f7faf7;
  font-size: 12px;
  text-align: center;
  padding: 10px;
  border-color: #e4ebe4;
}
.deployment-actions {
  margin-top: 20px;
}
@keyframes thinking {
  0%,
  80%,
  100% {
    opacity: 0.35;
  }
  40% {
    opacity: 1;
  }
}
@keyframes floating {
  0%,
  100% {
    transform: rotate(8deg) translateY(0);
  }
  50% {
    transform: rotate(2deg) translateY(-6px);
  }
}
@media (min-width: 1600px) {
  .workspace-body {
    padding: 22px;
    gap: 22px;
  }
  .conversation-panel {
    width: 34%;
  }
  .message-list {
    padding: 28px;
  }
}
@media (max-width: 1000px) {
  .conversation-panel {
    min-width: 320px;
    width: 42%;
  }
  .workspace-header {
    padding-right: 18px;
  }
  .preview-content {
    padding: 12px;
  }
  .app-heading-text h1 {
    max-width: 260px;
  }
}
@media (max-width: 760px) {
  #appChatPage {
    height: auto;
    min-height: 100dvh;
  }
  .workspace-header {
    min-height: 74px;
    height: auto;
    padding: 14px 12px;
    flex-wrap: wrap;
    gap: 12px;
  }
  .workspace-heading {
    gap: 9px;
    flex: 1;
  }
  .app-heading-text h1 {
    max-width: 220px;
    font-size: 14px;
  }
  .app-heading-text > span {
    font-size: 10px;
  }
  .app-symbol {
    height: 34px;
    width: 34px;
    font-size: 23px;
    border-radius: 11px;
  }
  .header-actions {
    gap: 7px;
    margin-left: auto;
  }
  .header-actions :deep(.ant-btn) {
    padding: 0 10px;
    font-size: 11px;
    height: 31px;
  }
  .workspace-body {
    flex-direction: column;
    padding: 10px;
    gap: 12px;
  }
  .conversation-panel {
    width: 100%;
    max-width: none;
    min-width: 0;
    height: 580px;
  }
  .conversation-welcome {
    padding-top: 13px;
  }
  .preview-panel {
    height: 600px;
    flex: auto;
  }
  .workspace-feedback {
    padding: 70px 12px;
  }
  .preview-placeholder {
    padding: 25px 15px;
  }
  .preview-toolbar {
    padding: 0 14px;
  }
}
@media (max-width: 420px) {
  .workspace-heading {
    width: 100%;
    flex: auto;
  }
  .header-actions {
    padding-left: 43px;
    width: 100%;
  }
  .header-actions .deploy-button {
    margin-left: auto;
  }
  .preview-ready {
    display: none;
  }
  .edit-mode-button span {
    display: none;
  }
  .edit-mode-button {
    padding: 0 8px;
  }
  .visual-editing-hint {
    max-width: calc(100% - 20px);
    overflow: hidden;
    text-overflow: ellipsis;
    font-size: 9px;
  }
}
@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation: none !important;
    scroll-behavior: auto !important;
  }
}
</style>
