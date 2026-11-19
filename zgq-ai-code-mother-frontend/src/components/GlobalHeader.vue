<template>
  <a-layout-header class="header">
    <a-row :wrap="false">
      <!-- 左侧：Logo和标题 -->
      <a-col class="brand-column" flex="220px">
        <RouterLink to="/">
          <div class="header-left">
            <img class="logo" src="@/assets/rocket.png" alt="Logo" />
            <span class="site-title">AI 应用工坊</span>
          </div>
        </RouterLink>
      </a-col>
      <!-- 中间：导航菜单 -->
      <a-col class="navigation-column" flex="auto">
        <a-menu
          :selectedKeys="selectedKeys"
          mode="horizontal"
          :items="menuItems"
          @click="handleMenuClick"
        />
      </a-col>
      <!-- 右侧：用户操作区域 -->
      <a-col>
        <div class="user-login-status">
          <div v-if="loginUserStore.loginUser.id">
            <a-dropdown>
              <a-space class="user-trigger" tabindex="0">
                <a-avatar :src="defaultAvatar" style="background: #167d72">{{
                  (loginUserStore.loginUser.userName || '用户').slice(0, 1)
                }}</a-avatar>
                <span class="user-name">{{ loginUserStore.loginUser.userName || '用户' }}</span>
              </a-space>
              <template #overlay>
                <a-menu>
                  <a-menu-item @click="doLogout">
                    <LogoutOutlined />
                    退出登录
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
          <div v-else>
            <a-button type="primary" @click="goToLogin">登录 / 注册</a-button>
          </div>
        </div>
      </a-col>
    </a-row>
  </a-layout-header>
</template>

<script setup lang="ts">
import { computed, h } from 'vue'
import { useRoute, useRouter, type RouteMeta } from 'vue-router'
import { message, type MenuProps } from 'ant-design-vue'
import type { ItemType } from 'ant-design-vue/es/menu/src/interface'
import { HomeOutlined, LogoutOutlined } from '@ant-design/icons-vue'
import defaultAvatar from '@/assets/avatar.jpg'
import { userLogout } from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/loginUser'
import checkAccess from '@/access/checkAccess'

const loginUserStore = useLoginUserStore()
const router = useRouter()
const route = useRoute()
// 当前选中菜单
const selectedKeys = computed(() => [route.path])

const goToLogin = () => router.push({ path: '/user/login', query: { redirect: route.fullPath } })

// 处理菜单点击
const handleMenuClick: MenuProps['onClick'] = (e) => {
  const key = e.key as string
  // 跳转到对应页面
  if (key.startsWith('/')) {
    router.push(key)
  }
}

// 用户注销
const doLogout = async () => {
  try {
    const res = await userLogout()
    if (res.data.code !== 0) throw new Error(res.data.message || '退出登录失败')
    loginUserStore.setLoginUser({ userName: '未登录' })
    message.success('退出登录成功')
    await router.push('/')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '退出登录失败，请稍后重试')
  }
}

// 菜单配置项
const originItems = [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '主页',
    title: '主页',
  },
  {
    key: '/admin/appManage',
    label: '应用管理',
    title: '应用管理',
  },
  {
    key: '/admin/chatHistoryManage',
    label: '对话管理',
    title: '对话管理',
  },
  {
    key: '/admin/userManage',
    label: '用户管理',
    title: '用户管理',
  },
  {
    key: 'others',
    label: h('a', { href: 'https://www.codefather.cn', target: '_blank' }, '编程导航'),
    title: '编程导航',
  },
]

// 将菜单项转换为对应的路由项（菜单 key 即路由 path），找不到路由（如外链菜单）则返回空 meta
const menuToRouteItem = (menu: ItemType): { meta: RouteMeta } => {
  const menuKey = (menu as { key?: string } | null)?.key
  return router.getRoutes().find((route) => route.path === menuKey) ?? { meta: {} }
}

// 过滤菜单项
const filterMenus = (menus = [] as MenuProps['items']) => {
  return menus?.filter((menu) => {
    const item = menuToRouteItem(menu)
    // 路由配置了 hideInMenu 则不在菜单中展示
    if (item.meta?.hideInMenu) {
      return false
    }
    // 根据权限过滤菜单，有权限则返回 true，保留该菜单
    return checkAccess(loginUserStore.loginUser, item.meta?.access as string)
  })
}

// 展示在菜单的路由数组
const menuItems = computed<MenuProps['items']>(() => filterMenus(originItems))
</script>

<style scoped>
.header {
  position: relative;
  z-index: 10;
  height: auto;
  min-height: 72px;
  line-height: 72px;
  background: #fff;
  padding: 0 clamp(20px, 4vw, 64px);
  border-bottom: 1px solid #edf1ef;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  height: 38px;
  width: 38px;
  object-fit: contain;
}

.site-title {
  margin: 0;
  font-size: 18px;
  color: #172b29;
  font-weight: 700;
  letter-spacing: -0.5px;
}

.user-trigger {
  cursor: pointer;
}
.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.navigation-column {
  min-width: 0;
}

.ant-menu-horizontal {
  border-bottom: none !important;
}

@media (max-width: 640px) {
  .header {
    padding: 0 16px;
    line-height: 60px;
    min-height: 60px;
  }
  .header :deep(.ant-row) {
    flex-wrap: wrap;
  }
  .brand-column {
    flex: 1 1 auto !important;
  }
  .navigation-column {
    flex: 1 0 100% !important;
    order: 3;
  }
  .navigation-column :deep(.ant-menu) {
    line-height: 42px;
  }
  .logo {
    width: 30px;
    height: 30px;
  }
  .site-title {
    font-size: 16px;
  }
  .user-name {
    display: none;
  }
}
</style>
