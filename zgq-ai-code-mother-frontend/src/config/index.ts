/** REST 和 SSE 使用的后端 API 根地址。 */
export const API_BASE_URL = (
  import.meta.env.VITE_API_BASE_URL?.trim() || 'http://localhost:8123/api'
).replace(/\/+$/, '')

/** 生成站点预览根地址；未单独配置时沿用 API 根地址。 */
export const APP_PREVIEW_BASE_URL = (
  import.meta.env.VITE_APP_PREVIEW_BASE_URL?.trim() || API_BASE_URL
).replace(/\/+$/, '')
