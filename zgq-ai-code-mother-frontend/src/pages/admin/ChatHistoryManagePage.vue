<template>
  <div id="chatHistoryManagePage">
    <a-result
      v-if="!loadingUser && !isAdmin"
      status="403"
      title="无权访问"
      sub-title="仅管理员可以查看全部对话历史。"
    >
      <template #extra><a-button @click="router.push('/')">返回首页</a-button></template>
    </a-result>
    <a-spin v-else-if="loadingUser" tip="正在验证权限…" />
    <template v-else>
      <div class="page-heading">
        <div>
          <h1>对话管理</h1>
          <p>跨应用查询、审阅和管理全部用户与 AI 消息</p>
        </div>
        <a-button :loading="loading" @click="fetchData">刷新列表</a-button>
      </div>

      <a-form layout="inline" :model="searchParams" class="search-form" @finish="doSearch">
        <a-form-item label="应用 ID">
          <a-input v-model:value="searchParams.appId" placeholder="输入应用 ID" allow-clear />
        </a-form-item>
        <a-form-item label="用户 ID">
          <a-input v-model:value="searchParams.userId" placeholder="输入用户 ID" allow-clear />
        </a-form-item>
        <a-form-item label="消息类型">
          <a-select
            v-model:value="searchParams.messageType"
            allow-clear
            placeholder="全部类型"
            :options="messageTypeOptions"
          />
        </a-form-item>
        <a-form-item label="每页条数">
          <a-input-number
            v-model:value="searchParams.pageSize"
            :min="1"
            :max="100"
            :precision="0"
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" html-type="submit" :loading="loading">搜索</a-button>
            <a-button @click="resetSearch">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>

      <a-divider />
      <a-alert v-if="loadError" class="error-alert" type="error" show-icon :message="loadError">
        <template #action>
          <a-button size="small" :loading="loading" @click="fetchData">重试</a-button>
        </template>
      </a-alert>

      <a-table
        :data-source="data"
        :columns="columns"
        :row-key="rowKey"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 1150 }"
        @change="doTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'appId'">
            <a-button type="link" size="small" class="id-link" @click="filterByApp(record.appId)">
              {{ record.appId }}
            </a-button>
          </template>
          <template v-else-if="column.dataIndex === 'messageType'">
            <a-tag :color="record.messageType === 'user' ? 'blue' : 'green'">
              {{ record.messageType === 'user' ? '用户' : 'AI' }}
            </a-tag>
          </template>
          <template v-else-if="column.dataIndex === 'message'">
            <span class="message-preview">{{ record.message || '—' }}</span>
          </template>
          <template v-else-if="column.dataIndex === 'createTime'">
            {{ formatDateTime(record.createTime) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space>
              <a-button size="small" @click="openDetail(record)">详情</a-button>
              <a-button size="small" :disabled="!record.appId" @click="openApp(record)">
                打开应用
              </a-button>
            </a-space>
          </template>
        </template>
      </a-table>

      <a-modal v-model:open="detailOpen" title="对话详情" :footer="null" :width="820">
        <template v-if="detail">
          <a-descriptions bordered :column="2" size="small">
            <a-descriptions-item label="消息 ID">{{ detail.id || '—' }}</a-descriptions-item>
            <a-descriptions-item label="消息类型">
              {{ detail.messageType === 'user' ? '用户消息' : 'AI 回复' }}
            </a-descriptions-item>
            <a-descriptions-item label="应用 ID">{{ detail.appId || '—' }}</a-descriptions-item>
            <a-descriptions-item label="用户 ID">{{ detail.userId || '—' }}</a-descriptions-item>
            <a-descriptions-item label="创建时间">
              {{ formatDateTime(detail.createTime) }}
            </a-descriptions-item>
            <a-descriptions-item label="更新时间">
              {{ formatDateTime(detail.updateTime) }}
            </a-descriptions-item>
          </a-descriptions>
          <div class="message-detail">
            <div class="message-detail-heading">消息内容</div>
            <MarkdownContent
              v-if="detail.messageType === 'ai' && detail.message"
              :content="detail.message"
            />
            <pre v-else class="message-plain">{{ detail.message || '—' }}</pre>
          </div>
        </template>
      </a-modal>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { TablePaginationConfig } from 'ant-design-vue'
import MarkdownContent from '@/components/MarkdownContent.vue'
import { listChatHistoryForAdmin } from '@/api/chatHistoryController'
import { useLoginUserStore } from '@/stores/loginUser'
import { formatDateTime, isValidAppId } from '@/utils/app'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')
const loadingUser = ref(true)
const data = ref<API.ChatHistory[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
let listRequest = 0

type SearchParams = {
  pageNum: number
  pageSize: number
  appId: string
  userId: string
  messageType?: string
}

/** 返回一份全新的搜索条件，避免重置时复用被修改的对象。 */
const initialSearch = (): SearchParams => ({
  pageNum: 1,
  pageSize: 10,
  appId: '',
  userId: '',
  messageType: undefined,
})

const searchParams = reactive<SearchParams>(initialSearch())
const messageTypeOptions = [
  { label: '用户消息', value: 'user' },
  { label: 'AI 回复', value: 'ai' },
]
const columns = [
  { title: '消息 ID', dataIndex: 'id', width: 180, ellipsis: true },
  { title: '应用 ID', dataIndex: 'appId', width: 190 },
  { title: '用户 ID', dataIndex: 'userId', width: 190 },
  { title: '类型', dataIndex: 'messageType', width: 70 },
  { title: '消息内容', dataIndex: 'message', width: 210, ellipsis: true },
  { title: '创建时间', dataIndex: 'createTime', width: 150 },
  { title: '操作', key: 'action', width: 150, fixed: 'right' as const },
]
const rowKey = (record: API.ChatHistory) => String(record.id)
const errorText = (error: unknown, fallback: string) =>
  error instanceof Error ? error.message : fallback
const pagination = computed(() => ({
  current: searchParams.pageNum,
  pageSize: searchParams.pageSize,
  total: total.value,
  showSizeChanger: true,
  showQuickJumper: true,
  pageSizeOptions: ['10', '20', '50', '100'],
  showTotal: (count: number) => `共 ${count} 条`,
}))

/** 按筛选条件加载一页全局对话历史，并只采纳最后一次请求的结果。 */
const fetchData = async () => {
  if (!isAdmin.value) return
  const appId = searchParams.appId.trim()
  const userId = searchParams.userId.trim()
  if ((appId && !isValidAppId(appId)) || (userId && !isValidAppId(userId))) {
    message.warning('请输入有效的应用 ID 和用户 ID')
    return
  }
  if (
    !Number.isSafeInteger(searchParams.pageSize) ||
    searchParams.pageSize < 1 ||
    searchParams.pageSize > 100
  ) {
    message.warning('每页条数必须为 1 到 100 的整数')
    return
  }
  const requestId = ++listRequest
  loading.value = true
  loadError.value = ''
  try {
    const res = await listChatHistoryForAdmin({
      pageNum: searchParams.pageNum,
      pageSize: searchParams.pageSize,
      appId: appId || undefined,
      userId: userId || undefined,
      messageType: searchParams.messageType || undefined,
    })
    if (requestId !== listRequest) return
    if (res.data.code !== 0 || !res.data.data) {
      throw new Error(res.data.message || '获取对话历史失败')
    }
    data.value = res.data.data.records ?? []
    // 后端把 Long 序列化为字符串；分页总数需要转成 number 供表格使用。
    total.value = Number(res.data.data.totalRow ?? 0)
  } catch (error) {
    if (requestId === listRequest) {
      data.value = []
      total.value = 0
      loadError.value = errorText(error, '获取对话历史失败，请稍后重试')
    }
  } finally {
    if (requestId === listRequest) loading.value = false
  }
}

/** 提交搜索时回到第一页，避免停留在超出结果范围的页码。 */
const doSearch = () => {
  searchParams.pageNum = 1
  void fetchData()
}

/** 恢复初始筛选条件并重新查询。 */
const resetSearch = () => {
  Object.assign(searchParams, initialSearch())
  void fetchData()
}

/** 表格分页变化时同步页码和每页条数。 */
const doTableChange = (page: TablePaginationConfig) => {
  searchParams.pageNum = page.current ?? 1
  searchParams.pageSize = page.pageSize ?? 10
  void fetchData()
}

/** 以某个应用 ID 重新筛选，便于从应用管理页跳转后继续定位。 */
const filterByApp = (appId: unknown) => {
  if (!appId) return
  searchParams.appId = String(appId)
  searchParams.pageNum = 1
  void fetchData()
}

const detailOpen = ref(false)
const detail = ref<API.ChatHistory>()

/** 展示当前消息的完整内容，不额外请求接口。 */
const openDetail = (record: API.ChatHistory) => {
  detail.value = record
  detailOpen.value = true
}

/** 打开消息所属应用的对话工作台，管理员可只读查看。 */
const openApp = (record: API.ChatHistory) => {
  if (!record.appId) return
  void router.push(`/app/chat/${record.appId}`)
}

onMounted(async () => {
  try {
    if (!loginUserStore.loginUser.id) await loginUserStore.fetchLoginUser()
  } catch {
    message.error('登录状态获取失败，请刷新页面重试')
  } finally {
    loadingUser.value = false
  }
  if (!isAdmin.value) return
  const queryAppId = typeof route.query.appId === 'string' ? route.query.appId : ''
  if (queryAppId && isValidAppId(queryAppId)) searchParams.appId = queryAppId
  await fetchData()
})
</script>

<style scoped>
#chatHistoryManagePage {
  padding: 4px;
}
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
}
.page-heading h1 {
  margin: 0 0 6px;
  font-size: 24px;
  font-weight: 650;
}
.page-heading p {
  margin: 0;
  color: #87908e;
}
.search-form {
  gap: 12px 0;
}
.search-form :deep(.ant-input),
.search-form :deep(.ant-select),
.search-form :deep(.ant-input-number) {
  width: 170px;
}
.error-alert {
  margin-bottom: 16px;
}
.id-link {
  padding: 0;
  height: auto;
}
.message-preview {
  display: -webkit-box;
  overflow: hidden;
  color: #4d5f55;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}
.message-detail {
  max-height: 55vh;
  margin-top: 16px;
  padding: 16px;
  overflow: auto;
  border: 1px solid #e7ece9;
  border-radius: 10px;
  background: #fafcfb;
}
.message-detail-heading {
  margin-bottom: 10px;
  color: #89928f;
  font-size: 12px;
}
.message-detail :deep(.markdown-content) {
  max-width: none;
}
.message-plain {
  margin: 0;
  color: #405b4c;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 640px) {
  #chatHistoryManagePage {
    padding: 0;
  }
  .page-heading {
    align-items: flex-start;
  }
}
</style>
