<template>
  <div id="appEditPage">
    <a-button class="back-button" type="text" @click="goBack">← 返回</a-button>
    <div class="page-heading">
      <span class="eyebrow">APPLICATION SETTINGS</span>
      <h1>应用信息</h1>
      <p>{{ isAdmin ? '管理应用名称、封面和展示优先级' : '为你的创意取一个好名字' }}</p>
    </div>
    <a-spin :spinning="loading" tip="正在加载应用信息…">
      <a-alert v-if="loadError" type="error" show-icon :message="loadError" class="load-error">
        <template #action
          ><a-button size="small" :loading="loading" @click="loadApp">重试</a-button></template
        >
      </a-alert>
      <a-result
        v-else-if="!loading && !canEdit"
        status="403"
        title="无权编辑"
        sub-title="你只能修改自己创建的应用。"
      >
        <template #extra
          ><a-button type="primary" @click="router.push('/')">返回首页</a-button></template
        >
      </a-result>
      <template v-else-if="app && canEdit">
        <div class="settings-card">
          <div class="card-title">
            <h2>基本信息</h2>
            <a-tag :color="isAdmin ? 'gold' : 'green'">{{
              isAdmin ? '管理员编辑' : '我的应用'
            }}</a-tag>
          </div>
          <a-form ref="formRef" layout="vertical" :model="form" :rules="rules" @finish="saveApp">
            <a-form-item label="应用名称" name="appName"
              ><a-input
                v-model:value="form.appName"
                placeholder="请输入应用名称"
                :maxlength="256"
                show-count
            /></a-form-item>
            <template v-if="isAdmin">
              <a-form-item label="应用封面" name="cover"
                ><a-input
                  v-model:value="form.cover"
                  placeholder="输入 http:// 或 https:// 开头的图片地址"
                  :maxlength="1024"
                  allow-clear
                />
                <div class="field-help">
                  填写图片链接后可预览；留空表示不设置封面。
                </div></a-form-item
              >
              <div v-if="safeCover" class="cover-preview">
                <a-image :src="safeCover" :width="260" :height="160" /><span>封面预览</span>
              </div>
              <a-form-item label="优先级" name="priority"
                ><a-input-number
                  v-model:value="form.priority"
                  :precision="0"
                  :min="-2147483648"
                  :max="2147483647"
                />
                <div class="field-help">设为 99 后，应用进入精选列表。</div></a-form-item
              >
            </template>
            <a-alert
              v-if="saveError"
              type="error"
              show-icon
              :message="saveError"
              class="save-error"
            />
            <a-space
              ><a-button type="primary" html-type="submit" :loading="saving" :disabled="loading"
                >保存修改</a-button
              ><a-button :disabled="saving" @click="goBack">返回</a-button
              ><a-button
                type="link"
                :disabled="saving"
                @click="router.push(`/app/chat/${String(app.id)}`)"
                >打开应用</a-button
              ></a-space
            >
          </a-form>
        </div>
        <div class="settings-card readonly-card">
          <h2>应用详情</h2>
          <a-descriptions :column="1" size="small">
            <a-descriptions-item label="应用 ID">{{ app.id }}</a-descriptions-item>
            <a-descriptions-item label="创建用户 ID">{{ app.userId ?? '—' }}</a-descriptions-item>
            <a-descriptions-item label="生成类型">
              <CodeGenTypeTag
                v-if="app.codeGenType"
                :type="app.codeGenType"
                :text="app.codeGenTypeText"
              />
              <span v-else>尚未生成</span>
            </a-descriptions-item>
            <a-descriptions-item label="部署标识">{{
              app.deployKey || '尚未部署'
            }}</a-descriptions-item>
            <a-descriptions-item label="初始提示词"
              ><span class="prompt-text">{{ app.initPrompt || '—' }}</span></a-descriptions-item
            >
            <a-descriptions-item label="创建时间">{{
              formatDateTime(app.createTime)
            }}</a-descriptions-item>
            <a-descriptions-item label="编辑时间">{{
              formatDateTime(app.editTime)
            }}</a-descriptions-item>
            <a-descriptions-item label="更新时间">{{
              formatDateTime(app.updateTime)
            }}</a-descriptions-item>
            <a-descriptions-item label="部署时间">{{
              formatDateTime(app.deployedTime)
            }}</a-descriptions-item>
          </a-descriptions>
        </div>
      </template>
      <div v-else-if="loading" class="loading-placeholder" />
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { adminUpdateApp, getAppById, getAppVoById, updateApp } from '@/api/appController'
import CodeGenTypeTag from '@/components/CodeGenTypeTag.vue'
import { useLoginUserStore } from '@/stores/loginUser'
import { formatDateTime, isSameId, isValidAppId, toSafeHttpUrl } from '@/utils/app'
import { message } from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import type { Rule } from 'ant-design-vue/es/form'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()
const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')
const app = ref<API.App | API.AppVO>()
const canEdit = computed(
  () => !!app.value && (isAdmin.value || isSameId(app.value.userId, loginUserStore.loginUser.id)),
)
const loading = ref(true)
const saving = ref(false)
const loadError = ref('')
const saveError = ref('')
const formRef = ref<FormInstance>()
const form = reactive({ appName: '', cover: '', priority: 0 as number | undefined })
let loadRequest = 0
const safeCover = computed(() => toSafeHttpUrl(form.cover.trim()))
const rules: Record<string, Rule[]> = {
  appName: [
    { required: true, whitespace: true, message: '请输入应用名称', trigger: 'blur' },
    { max: 256, message: '应用名称最多 256 个字符', trigger: 'blur' },
  ],
  cover: [
    { max: 1024, message: '封面地址最多 1024 个字符', trigger: 'blur' },
    {
      validator: async (_rule, value: string) => {
        if (value?.trim() && !safeCover.value)
          throw new Error('请输入有效的 HTTP 或 HTTPS 图片地址')
      },
      trigger: 'blur',
    },
  ],
  priority: [
    {
      required: true,
      type: 'integer',
      min: -2147483648,
      max: 2147483647,
      message: '请输入有效的整数优先级',
      trigger: 'change',
    },
  ],
}
const errorText = (error: unknown, fallback: string) =>
  error instanceof Error ? error.message : fallback
const goBack = () => {
  if (window.history.state?.back) router.back()
  else void router.push(isAdmin.value ? '/admin/appManage' : '/')
}

const loadApp = async () => {
  const requestId = ++loadRequest
  loading.value = true
  loadError.value = ''
  saveError.value = ''
  app.value = undefined
  try {
    const rawId = route.params.id
    const id = Array.isArray(rawId) ? rawId[0] : rawId
    if (!id || !isValidAppId(id)) throw new Error('应用 ID 无效，请检查页面地址')
    if (!loginUserStore.loginUser.id) await loginUserStore.fetchLoginUser()
    if (!loginUserStore.loginUser.id) throw new Error('请先登录后再编辑应用')
    const params = { id } as unknown as API.getAppByIdParams
    const res = isAdmin.value ? await getAppById(params) : await getAppVoById(params)
    if (requestId !== loadRequest) return
    if (res.data.code !== 0 || !res.data.data)
      throw new Error(res.data.message || '获取应用信息失败')
    app.value = res.data.data
    form.appName = app.value.appName ?? ''
    form.cover = app.value.cover ?? ''
    form.priority = app.value.priority ?? 0
    formRef.value?.clearValidate()
  } catch (error) {
    if (requestId === loadRequest) loadError.value = errorText(error, '获取应用信息失败，请重试')
  } finally {
    if (requestId === loadRequest) loading.value = false
  }
}
const saveApp = async () => {
  if (saving.value || !app.value || !canEdit.value) return
  const id = app.value.id
  if (!id) return
  saving.value = true
  saveError.value = ''
  try {
    const appName = form.appName.trim()
    const res = isAdmin.value
      ? await adminUpdateApp({ id, appName, cover: form.cover.trim(), priority: form.priority })
      : await updateApp({ id, appName })
    if (res.data.code !== 0 || !res.data.data) throw new Error(res.data.message || '保存失败')
    if (!isSameId(app.value?.id, id)) return
    app.value.appName = appName
    form.appName = appName
    if (isAdmin.value) {
      app.value.cover = form.cover.trim()
      app.value.priority = form.priority
    }
    message.success('应用信息已保存')
    await loadApp()
  } catch (error) {
    saveError.value = errorText(error, '保存失败，请重试')
  } finally {
    saving.value = false
  }
}
watch(
  () => route.params.id,
  () => {
    void loadApp()
  },
  { immediate: true },
)
</script>

<style scoped>
#appEditPage {
  width: min(760px, 100%);
  margin: 0 auto;
  padding: 12px 0 40px;
}
.back-button {
  padding-left: 0;
  color: #687873;
}
.page-heading {
  margin: 26px 0 28px;
}
.eyebrow {
  color: #6a9185;
  font-size: 11px;
  letter-spacing: 2px;
  font-weight: 600;
}
.page-heading h1 {
  margin: 10px 0;
  font-size: 32px;
  letter-spacing: -1px;
}
.page-heading p {
  color: #7c8884;
  margin: 0;
}
.settings-card {
  padding: 28px;
  background: #fff;
  border: 1px solid #e7eeeb;
  border-radius: 18px;
  box-shadow: 0 5px 25px #213d3210;
  margin-bottom: 20px;
}
.card-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}
.settings-card h2 {
  font-size: 17px;
  font-weight: 600;
  margin: 0;
}
.readonly-card h2 {
  margin-bottom: 22px;
}
.field-help {
  margin-top: 6px;
  color: #8c9793;
  font-size: 12px;
}
.cover-preview {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: -8px 0 24px;
  color: #8c9793;
  font-size: 12px;
}
.cover-preview :deep(img) {
  object-fit: cover;
  border-radius: 8px;
}
.save-error {
  margin-bottom: 20px;
}
.load-error {
  margin-top: 24px;
}
.loading-placeholder {
  min-height: 300px;
}
.prompt-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 640px) {
  #appEditPage {
    padding-top: 0;
  }
  .settings-card {
    padding: 20px;
  }
  .page-heading h1 {
    font-size: 28px;
  }
}
</style>
