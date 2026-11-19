import { API_BASE_URL } from '@/config'

/**
 * 从 Content-Disposition 响应头中提取文件名。
 * Spring 会同时输出普通 filename 和 RFC 5987 的 filename*，优先使用支持中文的后者。
 */
export function parseDownloadFileName(contentDisposition: string | null): string {
  if (!contentDisposition) return ''
  const encodedMatch = contentDisposition.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)
  if (encodedMatch?.[1]) {
    try {
      return decodeURIComponent(encodedMatch[1].trim().replace(/^"|"$/g, ''))
    } catch {
      // 编码异常时回退到普通 filename，避免整个下载流程失败。
    }
  }
  const plainMatch = contentDisposition.match(/filename\s*=\s*"([^"]*)"/i)
  return plainMatch?.[1]?.trim() || ''
}

/**
 * 携带 Session 下载应用源代码 ZIP。
 *
 * 后端校验失败时沿用项目约定返回 HTTP 200 的 BaseResponse JSON，因此不能只判断 response.ok，
 * 必须检查 Content-Type；只有真正的 ZIP 才触发浏览器保存文件。
 */
export async function downloadAppCode(appId: string, fallbackFileName: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/app/download/${encodeURIComponent(appId)}`, {
    method: 'GET',
    credentials: 'include',
    cache: 'no-store',
  })
  const contentType = response.headers.get('content-type') || ''
  if (!response.ok || !contentType.includes('application/zip')) {
    let reason = '下载代码失败，请稍后重试'
    try {
      const body = (await response.json()) as { message?: string; code?: number }
      reason = body.code === 40100 ? '登录已过期，请重新登录后下载' : body.message || reason
    } catch {
      // 网关或代理可能返回非 JSON 错误页，保留兜底提示。
    }
    throw new Error(reason)
  }
  const blob = await response.blob()
  if (blob.size === 0) throw new Error('服务端返回的代码压缩包为空')
  const fileName =
    parseDownloadFileName(response.headers.get('content-disposition')) || fallbackFileName
  const objectUrl = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = fileName
    link.style.display = 'none'
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    // 部分浏览器在 click 后才真正读取 Blob，延迟释放避免下载被取消。
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
  }
}
