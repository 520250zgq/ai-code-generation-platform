import { API_BASE_URL } from '@/config'

interface GenerateAppCodeOptions {
  appId: number
  message: string
  onChunk: (text: string) => void
  signal?: AbortSignal
}

/**
 * 使用 fetch 读取 SSE，携带 Session 并支持取消。
 * 不使用 EventSource 自动重连，避免重试连接重复触发一次代码生成。
 * 后端正常结束必须发送 event: done；普通 EOF 不代表生成已成功保存。
 */
export async function generateAppCode(options: GenerateAppCodeOptions): Promise<void> {
  const throwIfAborted = () => {
    if (options.signal?.aborted) throw new DOMException('生成已停止', 'AbortError')
  }
  throwIfAborted()
  const params = new URLSearchParams({ appId: String(options.appId), message: options.message })
  const response = await fetch(`${API_BASE_URL}/app/chat/gen/code?${params}`, {
    method: 'GET',
    credentials: 'include',
    headers: { Accept: 'text/event-stream' },
    signal: options.signal,
  })
  throwIfAborted()
  const contentType = response.headers.get('content-type') || ''
  if (!response.ok || !contentType.includes('text/event-stream')) {
    let reason = `生成请求失败（HTTP ${response.status}），请重试`
    try {
      const body = (await response.json()) as { message?: string; code?: number }
      reason = body.code === 40100 ? '登录已过期，请重新登录后生成' : body.message || reason
    } catch {
      // 代理或服务器可能返回非 JSON 错误页，保留可读的 HTTP 提示。
    }
    throw new Error(reason)
  }
  if (!response.body) throw new Error('浏览器无法读取流式响应，请更换浏览器后重试')

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let completed = false

  const consumeFrame = (frame: string) => {
    // onChunk may synchronously cancel; never consume later buffered events after cancellation.
    throwIfAborted()
    let event = 'message'
    const data: string[] = []
    for (const line of frame.split(/\r\n|\n|\r/)) {
      if (!line || line.startsWith(':')) continue
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      let value = colon < 0 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)
      if (field === 'event') event = value
      if (field === 'data') data.push(value)
    }
    const payload = data.join('\n')
    if (event === 'error') {
      let reason = payload || '生成失败，请重试'
      try {
        const body = JSON.parse(payload) as { message?: string }
        reason = body.message || reason
      } catch {
        // error 事件也可能直接返回纯文本。
      }
      throw new Error(reason)
    }
    if (event === 'done' || payload === '[DONE]') {
      completed = true
      return
    }
    if (!payload || !data.length) return
    let body: unknown
    try {
      body = JSON.parse(payload)
    } catch {
      throw new Error('无法解析生成结果，请重试')
    }
    if (typeof body === 'string') {
      options.onChunk(body)
    } else if (body && typeof body === 'object') {
      const result = body as { d?: unknown; code?: number; message?: string }
      if (result.code != null && result.code !== 0) {
        throw new Error(result.message || '生成失败，请重试')
      }
      if (typeof result.d !== 'string') throw new Error('生成结果格式不正确，请重试')
      options.onChunk(result.d)
    } else {
      throw new Error('生成结果格式不正确，请重试')
    }
  }

  try {
    while (!completed) {
      const { done, value } = await reader.read()
      throwIfAborted()
      buffer += decoder.decode(value, { stream: !done })
      // 在完整事件边界处分割，保留跨网络分片的 JSON、UTF-8 和换行。
      let boundary: RegExpExecArray | null
      while (!completed && (boundary = /\r\n\r\n|\n\n|\r\r/.exec(buffer))) {
        const frame = buffer.slice(0, boundary.index)
        buffer = buffer.slice(boundary.index + boundary[0].length)
        consumeFrame(frame)
      }
      if (done) break
    }
    throwIfAborted()
    if (!completed) throw new Error('连接已中断，尚未收到生成完成通知，请重试')
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
