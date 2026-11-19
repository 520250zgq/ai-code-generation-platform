<template>
  <AuthFormLayout id="userRegisterPage" title="AI 应用生成 - 用户注册">
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
      <a-form-item
        name="checkPassword"
        :rules="[
          { required: true, message: '请确认密码' },
          { min: 8, message: '密码不能小于 8 位' },
          { validator: validateCheckPassword },
        ]"
      >
        <a-input-password v-model:value="formState.checkPassword" placeholder="请确认密码" />
      </a-form-item>
      <div class="tips">
        已有账号？
        <RouterLink :to="{ path: '/user/login', query: route.query }">去登录</RouterLink>
      </div>
      <a-form-item>
        <a-button type="primary" html-type="submit" :loading="submitting" style="width: 100%"
          >注册</a-button
        >
      </a-form-item>
    </a-form>
  </AuthFormLayout>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { userRegister } from '@/api/userController.ts'
import { message } from 'ant-design-vue'
import AuthFormLayout from '@/components/AuthFormLayout.vue'

const router = useRouter()
const route = useRoute()
const submitting = ref(false)

const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

/**
 * 验证确认密码
 * @param rule
 * @param value
 * @param callback
 */
const validateCheckPassword = (rule: unknown, value: string, callback: (error?: Error) => void) => {
  if (value && value !== formState.userPassword) {
    callback(new Error('两次输入密码不一致'))
  } else {
    callback()
  }
}

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: API.UserRegisterRequest) => {
  if (submitting.value) return
  submitting.value = true
  try {
    const res = await userRegister(values)
    if (res.data.code !== 0) throw new Error(res.data.message || '注册失败')
    message.success('注册成功')
    await router.replace({
      path: '/user/login',
      query: route.query,
    })
  } catch (error) {
    message.error(error instanceof Error ? error.message : '注册失败，请检查网络后重试')
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
