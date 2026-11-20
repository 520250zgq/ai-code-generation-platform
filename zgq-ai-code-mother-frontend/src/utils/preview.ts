import { API_BASE_URL, APP_PREVIEW_BASE_URL } from '@/config'

/**
 * 生成站点预览状态。
 *
 * 后端在每次生成、Vue 构建开始时都会更新这个状态，前端据此判断“现在刷新 iframe 会不会
 * 看到最新效果”，而不是靠固定延时猜测构建是否完成。
 */
export interface PreviewStatus {
  appId?: string | number
  codeGenType?: string
  state: 'MISSING' | 'BUILDING' | 'READY' | 'FAILED'
  /** 版本号：每次生成或构建成功都会递增，用作 iframe 的强制刷新参数。 */
  revision: number
  ready: boolean
  /** 相对预览路径，例如 static/vue_project_123/；没有产物时为空。 */
  previewPath?: string
  message?: string
  updatedAtMillis?: number
}

/** 预览状态查询的超时时间，避免后端异常时前端一直等待。 */
const STATUS_TIMEOUT_MS = 10000

/** 轮询构建状态的时间间隔：构建通常需要几十秒，1 秒一次足够且不会造成压力。 */
const POLL_INTERVAL_MS = 1000

/**
 * 用预览相对路径和版本号拼接可直接放进 iframe 的地址。
 *
 * 版本号作为查询参数，确保浏览器不会复用上一轮的 iframe 缓存。
 */
export function buildPreviewUrl(status?: PreviewStatus): string {
  if (!status?.previewPath) return ''
  const base = `${APP_PREVIEW_BASE_URL}/${status.previewPath.replace(/^\/+/, '')}`
  return `${base}?v=${Number(status.revision) || 0}`
}

/**
 * 查询一次预览状态。
 *
 * 失败时返回 undefined 而不是抛错：预览只是体验优化，不能因为状态接口异常就打断
 * 已经成功的生成流程，调用方会回退到原有的探测逻辑。
 */
export async function getPreviewStatus(
  appId: number | string,
  signal?: AbortSignal,
): Promise<PreviewStatus | undefined> {
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), STATUS_TIMEOUT_MS)
  const abort = () => controller.abort()
  signal?.addEventListener('abort', abort, { once: true })
  try {
    const response = await fetch(
      `${API_BASE_URL}/app/preview/${encodeURIComponent(String(appId))}`,
      {
        method: 'GET',
        credentials: 'include',
        cache: 'no-store',
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      },
    )
    if (!response.ok) return undefined
    const body = (await response.json()) as {
      code?: number
      data?: PreviewStatus
      message?: string
    }
    if (body.code !== 0 || !body.data) return undefined
    return body.data
  } catch {
    return undefined
  } finally {
    window.clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

/**
 * 轮询预览状态，直到最新版本就绪或超时。
 *
 * @param appId   应用 id
 * @param options previousRevision 之前看到的版本号，用于判断“是否有新版本”
 * @param options timeoutMs 最长等待时间，默认 6 分钟（覆盖首次 npm install）
 * @param options onStatus 每次拿到状态时回调，便于界面展示进度
 * @param options signal 取消信号
 * @returns 就绪的状态；超时或取消时返回最后一次状态
 */
export async function waitForPreview(
  appId: number | string,
  options: {
    previousRevision?: number
    timeoutMs?: number
    onStatus?: (status: PreviewStatus) => void
    signal?: AbortSignal
  } = {},
): Promise<PreviewStatus | undefined> {
  const timeoutMs = options.timeoutMs ?? 6 * 60 * 1000
  const deadline = Date.now() + timeoutMs
  let lastStatus: PreviewStatus | undefined
  while (Date.now() < deadline) {
    if (options.signal?.aborted) return lastStatus
    const status = await getPreviewStatus(appId, options.signal)
    if (status) {
      lastStatus = status
      options.onStatus?.(status)
      const isNewRevision =
        options.previousRevision === undefined || Number(status.revision) > options.previousRevision
      if (status.ready && isNewRevision) return status
      // FAILED 不会自动恢复，继续轮询只会浪费请求，直接返回让界面提示失败。
      if (status.state === 'FAILED') return status
    }
    await new Promise((resolve) => window.setTimeout(resolve, POLL_INTERVAL_MS))
  }
  return lastStatus
}
