import { fileURLToPath, URL } from 'node:url'

import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // 读取所有环境变量，便于用 VITE_PROXY_TARGET 指向不同的本地后端。
  const env = loadEnv(mode, process.cwd(), '')
  const backendTarget = env.VITE_PROXY_TARGET?.trim() || 'http://localhost:8123'

  return {
    plugins: [vue(), vueDevTools()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      // 前端只请求同源的 /api，再由 Vite 转发到后端。
      // 后端 context-path 就是 /api，因此不需要 rewrite；/api/static 预览资源也会一起代理，
      // 这样 iframe 中的生成站点与父页面同源，才能动态注入可视化编辑脚本。
      proxy: {
        '/api': {
          target: backendTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
