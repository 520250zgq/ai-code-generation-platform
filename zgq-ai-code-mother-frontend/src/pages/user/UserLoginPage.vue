<template>
  <AuthFormLayout id="userLoginPage" title="AI 应用生成 - 用户登录">
    <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
      <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
        <a-input v-model:value="formState.userAccount" placeholder="请输入账号" />
      </a-form-item>
      <a-form-item
        name="userPassword"
        :rules="[
          { required: true, message: '请输入密码' },
          { min: 8, message: '密码不能小于 8 位' },
        ]"
      >
        <a-input-password v-model:value="formState.userPassword" placeholder="请输入密码" />
      </a-form-item>
      <div class="tips">
        没有账号？
        <RouterLink :to="{ path: '/user/register', query: route.query }">去注册</RouterLink>
      </div>
      <a-form-item>
        <a-button type="primary" html-type="submit" :loading="submitting" style="width: 100%"
          >登录</a-button
        >
      </a-form-item>
    </a-form>
  </AuthFormLayout>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { userLogin } from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/loginUser.ts'
import AuthFormLayout from '@/components/AuthFormLayout.vue'

const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

const router = useRouter()
const route = useRoute()
const submitting = ref(false)
const loginUserStore = useLoginUserStore()

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: API.UserLoginRequest) => {
  if (submitting.value) return
  submitting.value = true
  try {
    const res = await userLogin(values)
    if (res.data.code !== 0 || !res.data.data) throw new Error(res.data.message || '登录失败')
    loginUserStore.setLoginUser(res.data.data)
    message.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    // 只接受本站相对路径，防止 redirect 参数带来站外跳转。
    const destination =
      redirect.startsWith('/') &&
      !redirect.startsWith('//') &&
      !redirect.includes('\\') &&
      !/^\/user\/(login|register)/.test(redirect)
        ? redirect
        : '/'
    await router.replace(destination)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '登录失败，请检查网络后重试')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.tips {
  margin-bottom: 16px;
  color: #bbb;
  font-size: 13px;
  text-align: right;
}
</style>
