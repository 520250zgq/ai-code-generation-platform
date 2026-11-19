import { useLoginUserStore } from '@/stores/loginUser'
import { message } from 'ant-design-vue'
import router from '@/router'
import ACCESS_ENUM from '@/access/accessEnum'

// 是否为首次获取登录用户
let firstFetchLoginUser = true

/**
 * 全局权限校验
 */
router.beforeEach(async (to) => {
  const loginUserStore = useLoginUserStore()
  // 确保页面刷新，首次加载时，能够等后端返回用户信息后再校验权限
  if (firstFetchLoginUser) {
    await loginUserStore.fetchLoginUser()
    firstFetchLoginUser = false
  }
  const required = to.meta.access
  const loginUser = loginUserStore.loginUser
  if (required && required !== ACCESS_ENUM.NOT_LOGIN && !loginUser.id) {
    message.warning('请先登录')
    return { path: '/user/login', query: { redirect: to.fullPath } }
  }
  if (required === ACCESS_ENUM.ADMIN && loginUser.userRole !== ACCESS_ENUM.ADMIN) {
    message.error('仅管理员可以访问此页面')
    return '/'
  }
})
