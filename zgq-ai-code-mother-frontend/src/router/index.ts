import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'
import UserLoginPage from '@/pages/user/UserLoginPage.vue'
import UserRegisterPage from '@/pages/user/UserRegisterPage.vue'
import UserManagePage from '@/pages/admin/UserManagePage.vue'
import ACCESS_ENUM from '@/access/accessEnum'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: '主页',
      component: HomePage,
    },
    {
      path: '/user/login',
      name: '用户登录',
      component: UserLoginPage,
      meta: {
        hideInMenu: true,
      },
    },
    {
      path: '/user/register',
      name: '用户注册',
      component: UserRegisterPage,
      meta: {
        hideInMenu: true,
      },
    },
    {
      path: '/app/chat/:id',
      name: '应用生成',
      component: () => import('@/pages/app/AppChatPage.vue'),
      meta: { hideInMenu: true, access: ACCESS_ENUM.USER, fullscreen: true },
    },
    {
      path: '/app/edit/:id',
      name: '编辑应用',
      component: () => import('@/pages/app/AppEditPage.vue'),
      meta: { hideInMenu: true, access: ACCESS_ENUM.USER },
    },
    {
      path: '/admin/appManage',
      name: '应用管理',
      component: () => import('@/pages/admin/AppManagePage.vue'),
      meta: { access: ACCESS_ENUM.ADMIN },
    },
    {
      path: '/admin/chatHistoryManage',
      name: '对话管理',
      component: () => import('@/pages/admin/ChatHistoryManagePage.vue'),
      meta: { access: ACCESS_ENUM.ADMIN },
    },
    {
      path: '/admin/userManage',
      name: 'adminUserManage',
      component: UserManagePage,
      meta: {
        access: ACCESS_ENUM.ADMIN,
      },
    },
    {
      path: '/:pathMatch(.*)*',
      name: '页面不存在',
      component: () => import('@/pages/NotFoundPage.vue'),
      meta: { hideInMenu: true },
    },
  ],
})

export default router
