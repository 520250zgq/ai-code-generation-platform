<template>
  <div id="appManagePage">
    <a-result
      v-if="!loadingUser && !isAdmin"
      status="403"
      title="无权访问"
      sub-title="仅管理员可以管理全部应用。"
    >
      <template #extra><a-button @click="router.push('/')">返回首页</a-button></template>
    </a-result>
    <a-spin v-else-if="loadingUser" tip="正在验证权限…" />
    <template v-else>
      <div class="page-heading">
        <div>
          <h1>应用管理</h1>
          <p>查询、维护和精选站内的全部应用</p>
        </div>
        <a-button :loading="loading" @click="fetchData">刷新列表</a-button>
      </div>
      <a-form layout="inline" :model="searchParams" class="search-form" @finish="doSearch">
        <a-form-item label="应用 ID"
          ><a-input v-model:value="searchParams.id" placeholder="输入应用 ID" allow-clear
        /></a-form-item>
        <a-form-item label="应用名称"
          ><a-input v-model:value="searchParams.appName" placeholder="输入应用名称" allow-clear
        /></a-form-item>
        <a-form-item label="用户 ID"
          ><a-input v-model:value="searchParams.userId" placeholder="输入用户 ID" allow-clear
        /></a-form-item>
        <a-form-item label="应用封面"
          ><a-input v-model:value="searchParams.cover" placeholder="输入封面地址" allow-clear
        /></a-form-item>
        <a-form-item label="初始提示词"
          ><a-input
            v-model:value="searchParams.initPrompt"
            placeholder="输入提示词关键词"
            allow-clear
        /></a-form-item>
        <a-form-item label="生成类型"
          ><a-input
            v-model:value="searchParams.codeGenType"
            placeholder="如 html / multi_file / vue"
            allow-clear
        /></a-form-item>
        <a-form-item label="部署标识"
          ><a-input v-model:value="searchParams.deployKey" placeholder="输入部署标识" allow-clear
        /></a-form-item>
        <a-form-item label="优先级"
          ><a-input-number
            v-model:value="searchParams.priority"
            :precision="0"
            :min="-2147483648"
            :max="2147483647"
            placeholder="输入优先级"
        /></a-form-item>
        <a-form-item label="删除状态"
          ><a-select
            v-model:value="searchParams.isDelete"
            allow-clear
            placeholder="全部状态"
            :options="deleteOptions"
        /></a-form-item>
        <a-form-item label="排序字段"
          ><a-select
            v-model:value="searchParams.sortField"
            allow-clear
            placeholder="默认排序"
            :options="sortOptions"
        /></a-form-item>
        <a-form-item label="排序方向"
          ><a-select v-model:value="searchParams.sortOrder" :options="orderOptions"
        /></a-form-item>
        <a-form-item label="每页条数"
          ><a-input-number v-model:value="searchParams.pageSize" :min="1" :precision="0"
        /></a-form-item>
        <a-form-item
          ><a-space
            ><a-button type="primary" html-type="submit" :loading="loading">搜索</a-button
            ><a-button @click="resetSearch">重置</a-button></a-space
          ></a-form-item
        >
      </a-form>
      <a-divider />
      <a-alert v-if="loadError" class="error-alert" type="error" show-icon :message="loadError">
        <template #action
          ><a-button size="small" :loading="loading" @click="fetchData">重试</a-button></template
        >
      </a-alert>
      <a-table
        :data-source="data"
        :columns="columns"
        :row-key="rowKey"
        :loading="loading"
        :pagination="pagination"
        :scroll="{ x: 1500 }"
        @change="doTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'cover'">
            <a-image
              v-if="record.cover"
              :src="record.cover"
              :width="100"
              :height="62"
              class="cover-image"
            />
            <span v-else class="muted">暂无封面</span>
          </template>
          <template v-else-if="column.dataIndex === 'priority'"
            ><a-tag v-if="record.priority === 99" color="gold">精选 · 99</a-tag
            ><span v-else>{{ record.priority ?? 0 }}</span></template
          >
          <template v-else-if="column.dataIndex === 'isDelete'"
            ><a-tag :color="record.isDelete === 1 ? 'red' : 'green'">{{
              record.isDelete === 1 ? '已删除' : '正常'
            }}</a-tag></template
          >
          <template v-else-if="column.dataIndex === 'createTime'">{{
            formatDateTime(record.createTime)
          }}</template>
          <template v-else-if="column.key === 'action'">
            <a-space wrap>
              <a-button
                size="small"
                :disabled="record.isDelete === 1"
                @click="openChatHistory(record)"
                >对话</a-button
              >
              <a-button size="small" @click="openDetail(record)">详情</a-button>
              <a-button size="small" :disabled="record.isDelete === 1" @click="openEdit(record.id)"
                >编辑 ↗</a-button
              >
              <a-button
                size="small"
                :disabled="record.priority === 99 || !!actionBusy || record.isDelete === 1"
                :loading="actionBusy === `feature-${record.id}`"
                @click="setFeatured(record)"
                >{{ record.priority === 99 ? '已精选' : '精选' }}</a-button
              >
              <a-popconfirm
                title="确定删除这个应用？"
                description="删除后应用及其全部对话历史将无法继续访问。"
                ok-text="删除"
                cancel-text="取消"
                :disabled="!!actionBusy || record.isDelete === 1"
                @confirm="doDelete(record)"
              >
                <a-button
                  size="small"
                  danger
                  :disabled="!!actionBusy || record.isDelete === 1"
                  :loading="actionBusy === `delete-${record.id}`"
                  >删除</a-button
                >
              </a-popconfirm>
            </a-space>
          </template>
        </template>
      </a-table>
      <a-modal v-model:open="detailOpen" title="应用详情" :footer="null" :width="760">
        <a-spin :spinning="detailLoading">
          <a-alert v-if="detailError" type="error" show-icon :message="detailError"
            ><template #action
              ><a-button size="small" @click="fetchDetail">重试</a-button></template
            ></a-alert
          >
          <template v-else-if="detail">
            <a-alert
              v-if="detail.isDelete === 1"
              class="error-alert"
              type="info"
              show-icon
              message="该应用已删除，当前展示列表中的只读信息。"
            />
            <a-image v-if="detail.cover" :src="detail.cover" class="detail-cover" :width="200" />
            <a-descriptions bordered :column="1" size="small">
              <a-descriptions-item label="应用 ID">{{ detail.id }}</a-descriptions-item>
              <a-descriptions-item label="应用名称">{{
                detail.appName || '未命名应用'
              }}</a-descriptions-item>
              <a-descriptions-item label="创建用户 ID">{{
                detail.userId ?? '—'
              }}</a-descriptions-item>
              <a-descriptions-item label="初始提示词"
                ><span class="prompt-text">{{
                  detail.initPrompt || '—'
                }}</span></a-descriptions-item
              >
              <a-descriptions-item label="生成类型">
                <CodeGenTypeTag
                  v-if="detail.codeGenType"
                  :type="detail.codeGenType"
                  :text="detail.codeGenTypeText"
                />
                <span v-else>—</span>
              </a-descriptions-item>
              <a-descriptions-item label="封面地址"
                ><span class="break-word">{{ detail.cover || '—' }}</span></a-descriptions-item
              >
              <a-descriptions-item label="部署标识">{{
                detail.deployKey || '尚未部署'
              }}</a-descriptions-item>
              <a-descriptions-item label="优先级">{{ detail.priority ?? 0 }}</a-descriptions-item>
              <a-descriptions-item label="删除状态">{{
                detail.isDelete === 1 ? '已删除' : '正常'
              }}</a-descriptions-item>
              <a-descriptions-item label="创建时间">{{
                formatDateTime(detail.createTime)
              }}</a-descriptions-item>
              <a-descriptions-item label="编辑时间">{{
                formatDateTime(detail.editTime)
              }}</a-descriptions-item>
              <a-descriptions-item label="更新时间">{{
                formatDateTime(detail.updateTime)
              }}</a-descriptions-item>
              <a-descriptions-item label="部署时间">{{
                formatDateTime(detail.deployedTime)
              }}</a-descriptions-item>
            </a-descriptions>
            <a-button
              class="detail-edit"
              type="primary"
              :disabled="detail.isDelete === 1"
              @click="openEdit(detail.id)"
              >在新页面编辑</a-button
            >
          </template>
        </a-spin>
      </a-modal>
    </template>
  </div>
</template>

<script setup lang="ts">
import { adminUpdateApp, deleteApp, getAppById, listAppByPage } from '@/api/appController'
import CodeGenTypeTag from '@/components/CodeGenTypeTag.vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { formatDateTime, isValidAppId } from '@/utils/app'
import { message } from 'ant-design-vue'
import type { TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const loginUserStore = useLoginUserStore()
const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')
const loadingUser = ref(true)
const data = ref<API.App[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref('')
const actionBusy = ref('')
let listRequest = 0

type SearchParams = Omit<API.AppQueryRequest, 'id' | 'userId'> & { id: string; userId: string }
const initialSearch = (): SearchParams => ({
  pageNum: 1,
  pageSize: 10,
  id: '',
  userId: '',
  sortOrder: 'descend',
  sortField: undefined,
  appName: undefined,
  cover: undefined,
  initPrompt: undefined,
  codeGenType: undefined,
  deployKey: undefined,
  priority: undefined,
  isDelete: undefined,
})
const searchParams = reactive<SearchParams>(initialSearch())
const deleteOptions = [
  { label: '正常', value: 0 },
  { label: '已删除', value: 1 },
]
const orderOptions = [
  { label: '降序', value: 'descend' },
  { label: '升序', value: 'ascend' },
]
const sortOptions = [
  ['ID', 'id'],
  ['应用名称', 'appName'],
  ['封面', 'cover'],
  ['初始提示词', 'initPrompt'],
  ['生成类型', 'codeGenType'],
  ['部署标识', 'deployKey'],
  ['优先级', 'priority'],
  ['用户 ID', 'userId'],
  ['删除状态', 'isDelete'],
  ['创建时间', 'createTime'],
  ['编辑时间', 'editTime'],
  ['更新时间', 'updateTime'],
  ['部署时间', 'deployedTime'],
].map(([label, value]) => ({ label, value }))
const columns = [
  { title: 'ID', dataIndex: 'id', width: 180 },
  { title: '应用名称', dataIndex: 'appName', width: 170, ellipsis: true },
  { title: '封面', dataIndex: 'cover', width: 130 },
  { title: '初始提示词', dataIndex: 'initPrompt', width: 220, ellipsis: true },
  { title: '生成类型', dataIndex: 'codeGenType', width: 120 },
  { title: '用户 ID', dataIndex: 'userId', width: 180 },
  { title: '优先级', dataIndex: 'priority', width: 110 },
  { title: '状态', dataIndex: 'isDelete', width: 100 },
  { title: '创建时间', dataIndex: 'createTime', width: 180 },
  { title: '操作', key: 'action', width: 330, fixed: 'right' as const },
]
const rowKey = (record: API.App) => String(record.id)
const errorText = (error: unknown, fallback: string) =>
  error instanceof Error ? error.message : fallback
const pagination = computed(() => ({
  current: searchParams.pageNum ?? 1,
  pageSize: searchParams.pageSize ?? 10,
  total: total.value,
  showSizeChanger: true,
  showQuickJumper: true,
  pageSizeOptions: ['10', '20', '50', '100'],
  showTotal: (count: number) => `共 ${count} 条`,
}))

const fetchData = async () => {
  if (!isAdmin.value) return
  const id = searchParams.id.trim()
  const userId = searchParams.userId.trim()
  if ((id && !isValidAppId(id)) || (userId && !isValidAppId(userId))) {
    message.warning('请输入有效的应用 ID 和用户 ID')
    return
  }
  if (
    !searchParams.pageSize ||
    !Number.isSafeInteger(searchParams.pageSize) ||
    searchParams.pageSize < 1
  ) {
    message.warning('每页条数必须为正整数')
    return
  }
  const requestId = ++listRequest
  loading.value = true
  loadError.value = ''
  try {
    const filters = Object.fromEntries(
      Object.entries(searchParams).map(([key, value]) => [
        key,
        typeof value === 'string' ? value.trim() || undefined : (value ?? undefined),
      ]),
    )
    const query = {
      ...filters,
      id: id || undefined,
      userId: userId || undefined,
    } as unknown as API.AppQueryRequest
    const res = await listAppByPage(query)
    if (requestId !== listRequest) return
    if (res.data.code !== 0 || !res.data.data)
      throw new Error(res.data.message || '获取应用列表失败')
    data.value = res.data.data.records ?? []
    // 后端把 Long 序列化为字符串；分页计数需要 number，应用 ID 仍保持原始字符串。
    total.value = Number(res.data.data.totalRow ?? 0)
  } catch (error) {
    if (requestId === listRequest)
      loadError.value = errorText(error, '获取应用列表失败，请稍后重试')
  } finally {
    if (requestId === listRequest) loading.value = false
  }
}
const doSearch = () => {
  searchParams.pageNum = 1
  void fetchData()
}
const resetSearch = () => {
  Object.assign(searchParams, initialSearch())
  void fetchData()
}
const doTableChange = (page: TablePaginationConfig) => {
  searchParams.pageNum = page.current ?? 1
  searchParams.pageSize = page.pageSize ?? 10
  void fetchData()
}
const openEdit = (id: unknown) => {
  if (!id) return
  const target = router.resolve({ path: `/app/edit/${String(id)}` })
  window.open(target.href, '_blank', 'noopener,noreferrer')
}
/** 跳转到对话历史页并按当前应用预筛选。 */
const openChatHistory = (record: API.App) => {
  if (!record.id) return
  void router.push({ path: '/admin/chatHistoryManage', query: { appId: String(record.id) } })
}
const doDelete = async (record: API.App) => {
  if (!record.id || actionBusy.value) return
  actionBusy.value = `delete-${record.id}`
  try {
    const res = await deleteApp({ id: record.id })
    if (res.data.code !== 0 || !res.data.data) throw new Error(res.data.message || '删除失败')
    message.success('应用已删除')
    if (data.value.length === 1 && (searchParams.pageNum ?? 1) > 1)
      searchParams.pageNum = (searchParams.pageNum ?? 1) - 1
    await fetchData()
  } catch (error) {
    message.error(errorText(error, '删除失败，请重试'))
  } finally {
    actionBusy.value = ''
  }
}
const setFeatured = async (record: API.App) => {
  if (!record.id || actionBusy.value) return
  actionBusy.value = `feature-${record.id}`
  try {
    const res = await adminUpdateApp({ id: record.id, priority: 99 })
    if (res.data.code !== 0 || !res.data.data) throw new Error(res.data.message || '设置精选失败')
    message.success('已设为精选应用')
    await fetchData()
  } catch (error) {
    message.error(errorText(error, '设置精选失败，请重试'))
  } finally {
    actionBusy.value = ''
  }
}

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<API.App>()
const detailId = ref<API.App['id']>()
let detailRequest = 0
const fetchDetail = async () => {
  if (!detailId.value) return
  const requestId = ++detailRequest
  detailLoading.value = true
  detailError.value = ''
  try {
    const res = await getAppById({ id: detailId.value })
    if (requestId !== detailRequest) return
    if (res.data.code !== 0 || !res.data.data)
      throw new Error(res.data.message || '获取应用详情失败')
    detail.value = res.data.data
  } catch (error) {
    if (requestId === detailRequest) detailError.value = errorText(error, '获取应用详情失败')
  } finally {
    if (requestId === detailRequest) detailLoading.value = false
  }
}
const openDetail = (record: API.App) => {
  detailId.value = record.id
  detail.value = undefined
  detailError.value = ''
  detailOpen.value = true
  if (record.isDelete === 1) {
    ++detailRequest
    detailLoading.value = false
    detail.value = { ...record }
    return
  }
  void fetchDetail()
}
onMounted(async () => {
  try {
    if (!loginUserStore.loginUser.id) await loginUserStore.fetchLoginUser()
  } catch {
    message.error('登录状态获取失败，请刷新页面重试')
  } finally {
    loadingUser.value = false
  }
  if (isAdmin.value) await fetchData()
})
</script>

<style scoped>
#appManagePage {
  padding: 24px;
  background: #fff;
  border-radius: 16px;
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
.search-form :deep(.ant-input-affix-wrapper) {
  width: 170px;
}
.error-alert {
  margin-bottom: 16px;
}
.cover-image :deep(img) {
  object-fit: cover;
  border-radius: 6px;
}
.muted {
  color: #a1a6a5;
}
.detail-cover {
  margin-bottom: 20px;
}
.detail-edit {
  margin-top: 20px;
}
.prompt-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.break-word {
  overflow-wrap: anywhere;
}
@media (max-width: 640px) {
  #appManagePage {
    padding: 16px;
  }
  .page-heading {
    align-items: flex-start;
  }
}
</style>
