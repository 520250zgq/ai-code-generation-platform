<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  addApp,
  deleteApp,
  getAppVoById,
  listGoodAppVoByPage,
  listMyAppVoByPage,
} from '@/api/appController'
import AppCard from '@/components/AppCard.vue'
import AppDetailDescriptions from '@/components/AppDetailDescriptions.vue'
import { useLoginUserStore } from '@/stores/loginUser'

const router = useRouter()
const route = useRoute()
const loginUserStore = useLoginUserStore()
const loggedIn = computed(() => Boolean(loginUserStore.loginUser.id))
const creating = ref(false)
const prompt = ref('')
const suggestions = [
  {
    label: '波普风电商页面',
    prompt: '帮我创建一个波普艺术风格的电商首页，包含商品展示、商品分类、购物车和醒目的促销区域。',
  },
  {
    label: '企业网站',
    prompt:
      '帮我创建一个现代简洁的企业官网，包含公司介绍、核心服务、案例展示和联系我们，适配手机和电脑。',
  },
  {
    label: '电商运营后台',
    prompt: '帮我创建一个电商运营数据后台，包含数据概览、销售趋势、订单列表和商品管理页面。',
  },
  {
    label: '暗黑话题社区',
    prompt: '帮我创建一个暗黑风格的话题社区，包含热门话题、帖子列表、分类导航和帖子详情。',
  },
]

function createListState() {
  return reactive({
    apps: [] as API.AppVO[],
    name: '',
    activeName: '',
    pageNum: 1,
    pageSize: 6,
    total: 0,
    loading: false,
    error: '',
    requestId: 0,
  })
}
const myList = createListState()
const goodList = createListState()
type AppListState = ReturnType<typeof createListState>

async function fetchList(state: AppListState, mine: boolean) {
  const requestId = ++state.requestId
  if (!loggedIn.value) {
    state.apps = []
    state.total = 0
    state.error = ''
    state.loading = false
    return
  }
  state.loading = true
  state.error = ''
  try {
    const fetcher = mine ? listMyAppVoByPage : listGoodAppVoByPage
    const response = await fetcher({
      pageNum: state.pageNum,
      pageSize: Math.min(20, state.pageSize),
      appName: state.activeName || undefined,
      sortField: 'createTime',
      sortOrder: 'descend',
    })
    if (requestId !== state.requestId) return
    if (response.data.code !== 0 || !response.data.data)
      throw new Error(response.data.message || '应用列表加载失败')
    state.apps = response.data.data.records ?? []
    const total = Number(response.data.data.totalRow ?? 0)
    state.total = Number.isFinite(total) && total >= 0 ? total : 0
    const lastPage = Math.max(1, Math.ceil(state.total / state.pageSize))
    if (state.pageNum > lastPage) {
      state.pageNum = lastPage
      await fetchList(state, mine)
    }
  } catch (error) {
    if (requestId === state.requestId)
      state.error = error instanceof Error ? error.message : '网络连接失败，请稍后重试'
  } finally {
    if (requestId === state.requestId) state.loading = false
  }
}

function searchList(state: AppListState, mine: boolean) {
  state.activeName = state.name.trim()
  state.pageNum = 1
  void fetchList(state, mine)
}

function changePage(state: AppListState, mine: boolean, page: number, pageSize: number) {
  state.pageNum = pageSize !== state.pageSize ? 1 : page
  state.pageSize = Math.min(20, pageSize)
  void fetchList(state, mine)
}

function goToLogin() {
  void router.push({ path: '/user/login', query: { redirect: route.fullPath } })
}

async function createApp() {
  if (creating.value) return
  if (!prompt.value.trim()) {
    message.warning('先描述一下你想创建的网站吧')
    return
  }
  if (prompt.value.trim().length > 10000) {
    message.warning('提示词不能超过 10000 个字符')
    return
  }
  if (!loggedIn.value) {
    message.info('登录后即可开始创建应用')
    goToLogin()
    return
  }
  creating.value = true
  try {
    const response = await addApp({ initPrompt: prompt.value.trim() })
    const id = response.data.data
    if (response.data.code !== 0 || id === undefined || id === null)
      throw new Error(response.data.message || '创建应用失败')
    prompt.value = ''
    await router.push({ path: `/app/chat/${id}` })
  } catch (error) {
    message.error(error instanceof Error ? error.message : '创建应用失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

function onPromptKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !event.isComposing) {
    event.preventDefault()
    void createApp()
  }
}

const deletingId = ref<API.AppVO['id']>()
async function removeApp(app: API.AppVO) {
  if (app.id === undefined || app.id === null || deletingId.value !== undefined) return
  deletingId.value = app.id
  try {
    const response = await deleteApp({ id: app.id })
    if (response.data.code !== 0 || !response.data.data)
      throw new Error(response.data.message || '删除应用失败')
    message.success('应用已删除')
    if (myList.apps.length === 1 && myList.pageNum > 1) myList.pageNum -= 1
    await Promise.all([fetchList(myList, true), fetchList(goodList, false)])
  } catch (error) {
    message.error(error instanceof Error ? error.message : '删除应用失败，请稍后重试')
  } finally {
    deletingId.value = undefined
  }
}

const detailOpen = ref(false)
const detailLoading = ref(false)
const detailApp = ref<API.AppVO>()
const detailError = ref('')
const detailId = ref<API.AppVO['id']>()
let detailRequestId = 0
async function showDetails(app: API.AppVO) {
  if (app.id === undefined || app.id === null) return
  detailId.value = app.id
  detailApp.value = undefined
  detailOpen.value = true
  await fetchDetails()
}

async function fetchDetails() {
  const id = detailId.value
  if (id === undefined || id === null) return
  const requestId = ++detailRequestId
  detailLoading.value = true
  detailError.value = ''
  try {
    const response = await getAppVoById({ id })
    if (requestId !== detailRequestId) return
    if (response.data.code !== 0 || !response.data.data)
      throw new Error(response.data.message || '应用详情加载失败')
    detailApp.value = response.data.data
  } catch (error) {
    if (requestId === detailRequestId)
      detailError.value = error instanceof Error ? error.message : '应用详情加载失败'
  } finally {
    if (requestId === detailRequestId) detailLoading.value = false
  }
}

watch(
  () => loginUserStore.loginUser.id,
  () => {
    myList.pageNum = 1
    goodList.pageNum = 1
    void fetchList(myList, true)
    void fetchList(goodList, false)
  },
  { immediate: true },
)
onMounted(() => {
  try {
    prompt.value = sessionStorage.getItem('app-create-prompt') ?? ''
  } catch {
    /* 浏览器可能禁用本地存储 */
  }
})
watch(prompt, (value) => {
  try {
    if (value) sessionStorage.setItem('app-create-prompt', value)
    else sessionStorage.removeItem('app-create-prompt')
  } catch {
    /* 本地存储不可用时仍可正常创建 */
  }
})
</script>

<template>
  <main id="homePage" class="home-page">
    <section class="hero" aria-labelledby="heroTitle">
      <div class="hero-kicker"><span /> 灵感，从这一刻开始</div>
      <h1 id="heroTitle">一句话 <span class="hero-mark" aria-hidden="true">✦</span> 呈所想</h1>
      <p class="hero-description">与 AI 对话，轻松创建属于你的应用和网站</p>
      <div class="prompt-box">
        <a-textarea
          v-model:value="prompt"
          class="prompt-input"
          placeholder="描述你的想法，比如：为我的咖啡店创建一个温暖简约的网站…"
          :auto-size="{ minRows: 4, maxRows: 8 }"
          :disabled="creating"
          :maxlength="10000"
          aria-label="描述你想创建的网站"
          @keydown="onPromptKeydown"
        />
        <div class="prompt-toolbar">
          <span class="prompt-hint"
            ><span class="sparkle">✧</span>
            {{ creating ? '正在创建你的应用…' : '尽情描述，让 AI 帮你实现' }}</span
          >
          <a-button
            class="create-button"
            type="primary"
            :loading="creating"
            :disabled="!prompt.trim()"
            @click="createApp"
            ><span>{{ creating ? '创建中' : '开始创作' }}</span
            ><svg
              v-if="!creating"
              viewBox="0 0 24 24"
              width="18"
              height="18"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              aria-hidden="true"
            >
              <path d="M12 19V5M5 12l7-7 7 7" /></svg
          ></a-button>
        </div>
      </div>
      <div class="suggestions" aria-label="灵感提示">
        <span class="suggestion-label">试试这些灵感</span
        ><button
          v-for="item in suggestions"
          :key="item.label"
          type="button"
          :disabled="creating"
          @click="prompt = item.prompt"
        >
          {{ item.label }} <span aria-hidden="true">↗</span>
        </button>
      </div>
      <p class="keyboard-hint">Ctrl / ⌘ + Enter 快速开始</p>
    </section>

    <div class="applications-panel">
      <section class="app-section" aria-labelledby="myAppsTitle">
        <div class="section-header">
          <div>
            <div class="section-eyebrow">MY WORKSPACE</div>
            <h2 id="myAppsTitle">
              我的应用
              <span v-if="loggedIn && !myList.error" class="section-count">{{ myList.total }}</span>
            </h2>
            <p>每一个想法，都值得被实现</p>
          </div>
          <a-input-search
            v-if="loggedIn"
            v-model:value="myList.name"
            class="list-search"
            placeholder="搜索我的应用"
            allow-clear
            aria-label="搜索我的应用"
            :disabled="myList.loading"
            @search="searchList(myList, true)"
          />
        </div>
        <div v-if="!loggedIn" class="guest-state">
          <div class="state-icon" aria-hidden="true">✧</div>
          <h3>你的创作空间，正在等你</h3>
          <p>登录后创建应用，所有作品都会保存在这里</p>
          <a-button type="primary" @click="goToLogin">登录，开启创作</a-button>
        </div>
        <a-spin v-else :spinning="myList.loading" tip="正在加载应用…">
          <div class="list-body">
            <a-alert v-if="myList.error" type="error" show-icon :message="myList.error"
              ><template #action
                ><a-button size="small" @click="fetchList(myList, true)">重试</a-button></template
              ></a-alert
            >
            <div v-else-if="myList.apps.length" class="app-grid">
              <AppCard
                v-for="app in myList.apps"
                :key="String(app.id)"
                :app="app"
                manageable
                :deleting="deletingId === app.id"
                @detail="showDetails"
                @delete="removeApp"
              />
            </div>
            <a-empty
              v-else-if="!myList.loading"
              :description="
                myList.activeName
                  ? '没有找到匹配的应用，换个名称试试吧'
                  : '还没有应用，在上方输入想法开始创作吧'
              "
            />
            <div v-else class="loading-placeholder" />
          </div>
          <a-pagination
            v-if="myList.total > 0 && !myList.error"
            class="list-pagination"
            :current="myList.pageNum"
            :page-size="myList.pageSize"
            :total="myList.total"
            :page-size-options="['6', '12', '20']"
            show-size-changer
            :show-total="(total: number) => `共 ${total} 个应用`"
            @change="(page: number, size: number) => changePage(myList, true, page, size)"
          />
        </a-spin>
      </section>

      <section class="app-section" aria-labelledby="goodAppsTitle">
        <div class="section-header">
          <div>
            <div class="section-eyebrow">MADE WITH INSPIRATION</div>
            <h2 id="goodAppsTitle">
              精选应用 <span class="featured-star" aria-hidden="true">✦</span>
            </h2>
            <p>发现精彩作品，为下一个灵感充电</p>
          </div>
          <a-input-search
            v-if="loggedIn"
            v-model:value="goodList.name"
            class="list-search"
            placeholder="搜索精选应用"
            allow-clear
            aria-label="搜索精选应用"
            :disabled="goodList.loading"
            @search="searchList(goodList, false)"
          />
        </div>
        <div v-if="!loggedIn" class="guest-state">
          <div class="state-icon" aria-hidden="true">✦</div>
          <h3>发现更多精彩应用</h3>
          <p>登录后浏览精选作品，寻找下一个创作灵感</p>
          <a-button @click="goToLogin">登录查看精选</a-button>
        </div>
        <a-spin v-else :spinning="goodList.loading" tip="正在加载精选应用…">
          <div class="list-body">
            <a-alert v-if="goodList.error" type="error" show-icon :message="goodList.error"
              ><template #action
                ><a-button size="small" @click="fetchList(goodList, false)"
                  >重试</a-button
                ></template
              ></a-alert
            >
            <div v-else-if="goodList.apps.length" class="app-grid">
              <AppCard
                v-for="app in goodList.apps"
                :key="String(app.id)"
                :app="app"
                featured
                @detail="showDetails"
              />
            </div>
            <a-empty
              v-else-if="!goodList.loading"
              :description="
                goodList.activeName
                  ? '没有找到匹配的精选应用'
                  : '精选应用即将登场，期待你的作品入选'
              "
            />
            <div v-else class="loading-placeholder" />
          </div>
          <a-pagination
            v-if="goodList.total > 0 && !goodList.error"
            class="list-pagination"
            :current="goodList.pageNum"
            :page-size="goodList.pageSize"
            :total="goodList.total"
            :page-size-options="['6', '12', '20']"
            show-size-changer
            :show-total="(total: number) => `共 ${total} 个应用`"
            @change="(page: number, size: number) => changePage(goodList, false, page, size)"
          />
        </a-spin>
      </section>
    </div>

    <a-modal v-model:open="detailOpen" title="应用详情" :footer="null" :width="620">
      <a-spin :spinning="detailLoading"
        ><div class="detail-content">
          <a-alert v-if="detailError" type="error" show-icon :message="detailError"
            ><template #action
              ><a-button size="small" @click="fetchDetails">重试</a-button></template
            ></a-alert
          >
          <template v-else-if="detailApp">
            <AppDetailDescriptions v-if="detailApp" :app="detailApp" prompt-label="初始需求" />
            <div class="detail-actions">
              <a-button type="primary" @click="router.push(`/app/chat/${detailApp.id}`)"
                >打开应用</a-button
              >
            </div>
          </template>
        </div></a-spin
      >
    </a-modal>
  </main>
</template>

<style scoped>
.home-page {
  min-height: calc(100vh - 128px);
  margin: 0;
  padding: 0 28px 64px;
  color: #202c34;
  background:
    radial-gradient(ellipse 70% 36% at 100% 28%, #a9ebdeaa, transparent),
    radial-gradient(ellipse 90% 37% at 5% 52%, #b8eee7c9, transparent),
    linear-gradient(165deg, #fafcf8 8%, #f1faf6 35%, #d7ecff 76%, #edf5fc 100%);
}
.hero {
  max-width: 980px;
  margin: 0 auto;
  padding: 67px 0 40px;
  text-align: center;
}
.hero-kicker {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 21px;
  color: #51756b;
  font-size: 12px;
  letter-spacing: 2px;
}
.hero-kicker > span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #29b69b;
  box-shadow: 0 0 0 4px #29b69b13;
}
.hero h1 {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  margin: 0;
  color: #18262c;
  font-size: clamp(36px, 5vw, 64px);
  font-weight: 800;
  line-height: 1.3;
  letter-spacing: 2px;
}
.hero-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 66px;
  height: 66px;
  border-radius: 24px;
  background: #26e7bd;
  color: #12332f;
  font-size: 49px;
  transform: rotate(-8deg);
}
.hero-description {
  margin: 20px 0 35px;
  color: #72817e;
  font-size: 18px;
  letter-spacing: 1px;
}
.prompt-box {
  padding: 23px 24px 17px;
  border: 1px solid #ffffff;
  border-radius: 26px;
  background: #fffffff7;
  box-shadow: 0 14px 60px #517d7912;
  text-align: left;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}
.prompt-box:focus-within {
  border-color: #60cfb6;
  box-shadow:
    0 12px 40px #219d8a18,
    0 0 0 3px #b7eee530;
}
.prompt-input:deep(textarea),
.prompt-input {
  padding: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
  color: #33423d;
  font-size: 17px;
  line-height: 1.9;
  resize: none;
  box-shadow: none !important;
}
.prompt-input::placeholder {
  color: #a8b2b3;
}
.prompt-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 22px;
}
.prompt-hint {
  color: #9aa5a2;
  font-size: 12px;
}
.sparkle {
  margin-right: 5px;
  color: #6cae9f;
  font-size: 18px;
}
.create-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  height: 43px;
  padding: 0 20px;
  border: 0;
  border-radius: 13px;
  background: #1d3431;
  box-shadow: none;
  font-weight: 500;
}
.create-button:not(:disabled):hover {
  background: #2b5c51;
}
.create-button:disabled {
  background: #edf0ef;
  color: #a0aba7;
}
.suggestions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 9px;
  margin-top: 21px;
}
.suggestion-label {
  margin-right: 3px;
  color: #82948c;
  font-size: 12px;
}
.suggestions button {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 8px 13px;
  border: 1px solid #ffffffd6;
  border-radius: 10px;
  background: #ffffffc4;
  color: #60726a;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition:
    transform 0.2s,
    background 0.2s;
}
.suggestions button:hover {
  transform: translateY(-2px);
  background: #fff;
}
.suggestions button:disabled {
  cursor: wait;
}
.suggestions button span {
  color: #9eafa9;
}
.keyboard-hint {
  margin: 13px 0 0;
  color: #91a59a;
  font-size: 11px;
}
.applications-panel {
  max-width: 1260px;
  margin: 0 auto;
  padding: 39px 42px 43px;
  border: 1px solid #ffffffd9;
  border-radius: 28px;
  background: #ffffffed;
  box-shadow: 0 12px 60px #4a769a09;
}
.app-section + .app-section {
  margin-top: 49px;
  padding-top: 39px;
  border-top: 1px solid #f0f3f2;
}
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 25px;
}
.section-eyebrow {
  margin-bottom: 6px;
  color: #8fa49d;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 1.8px;
}
.section-header h2 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  color: #25332e;
  font-size: 25px;
  font-weight: 700;
}
.section-header p {
  margin: 8px 0 0;
  color: #99a39e;
  font-size: 12px;
}
.section-count {
  padding: 1px 8px;
  border-radius: 8px;
  background: #f0f6f3;
  color: #7d9588;
  font-size: 12px;
  font-weight: 500;
}
.featured-star {
  color: #37b294;
  font-size: 23px;
}
.list-search {
  width: 240px;
}
.list-search:deep(.ant-input-group .ant-input),
.list-search:deep(.ant-input-affix-wrapper) {
  background: #fafcfb;
}
.list-body {
  min-height: 190px;
}
.app-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 28px 22px;
}
.list-pagination {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  row-gap: 10px;
  margin-top: 27px;
}
.loading-placeholder {
  min-height: 230px;
}
.guest-state {
  padding: 26px 20px 30px;
  border: 1px dashed #dbe9e3;
  border-radius: 17px;
  background: linear-gradient(115deg, #fafdfc, #f5faf7);
  text-align: center;
}
.state-icon {
  margin-bottom: 7px;
  color: #38ad8f;
  font-size: 36px;
}
.guest-state h3 {
  margin: 0 0 8px;
  color: #52695f;
  font-size: 16px;
}
.guest-state p {
  margin: 0 0 20px;
  color: #95a39b;
  font-size: 13px;
}
.guest-state:deep(.ant-btn-primary) {
  border-radius: 9px;
  background: #248a71;
  box-shadow: none;
}
.detail-content {
  min-height: 100px;
  padding-top: 10px;
}
.detail-actions {
  margin-top: 20px;
  text-align: right;
}
@media (min-width: 1600px) {
  .hero {
    padding-top: 86px;
    padding-bottom: 53px;
  }
}
@media (max-width: 1000px) {
  .home-page {
    padding-right: 20px;
    padding-left: 20px;
  }
  .applications-panel {
    padding: 30px 25px;
  }
  .app-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .home-page {
    margin: 0;
    padding: 0 14px 32px;
  }
  .hero {
    padding-top: 41px;
    padding-bottom: 29px;
  }
  .hero h1 {
    gap: 11px;
    letter-spacing: 0;
  }
  .hero-mark {
    width: 45px;
    height: 45px;
    border-radius: 16px;
    font-size: 35px;
  }
  .hero-description {
    margin: 15px 0 25px;
    font-size: 13px;
    letter-spacing: 0;
  }
  .hero-kicker {
    font-size: 10px;
  }
  .prompt-box {
    padding: 18px 16px 14px;
    border-radius: 20px;
  }
  .prompt-input {
    font-size: 14px;
  }
  .prompt-hint {
    max-width: 145px;
    font-size: 10px;
  }
  .create-button {
    height: 39px;
    padding: 0 13px;
    font-size: 12px;
  }
  .suggestion-label {
    display: none;
  }
  .suggestions {
    gap: 7px;
  }
  .suggestions button {
    padding: 7px 9px;
    font-size: 11px;
  }
  .applications-panel {
    padding: 24px 17px;
    border-radius: 21px;
  }
  .section-header {
    flex-direction: column;
    align-items: stretch;
    gap: 16px;
    margin-bottom: 20px;
  }
  .section-header h2 {
    font-size: 22px;
  }
  .list-search {
    width: 100%;
  }
  .app-grid {
    grid-template-columns: 1fr;
    gap: 23px;
  }
  .app-section + .app-section {
    margin-top: 29px;
    padding-top: 28px;
  }
  .list-pagination {
    justify-content: center;
  }
}
@media (prefers-reduced-motion: reduce) {
  .prompt-box,
  .suggestions button {
    transition: none;
  }
}
</style>
