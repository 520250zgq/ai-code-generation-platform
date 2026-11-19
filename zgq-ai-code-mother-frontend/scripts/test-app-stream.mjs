/** Run with: node scripts/test-app-stream.mjs. No backend or extra packages are required. */
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))
const temporaryDirectory = await mkdtemp(join(tmpdir(), 'zgq-app-stream-test-'))
const bundledModule = join(temporaryDirectory, 'app-stream.mjs')

try {
  await build({
    absWorkingDir: projectRoot,
    entryPoints: ['src/utils/appStream.ts'],
    outfile: bundledModule,
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node22',
    alias: { '@': join(projectRoot, 'src') },
    define: {
      'import.meta.env': JSON.stringify({ VITE_API_BASE_URL: 'https://stream.test/api' }),
    },
    logLevel: 'silent',
  })
} catch (error) {
  await rm(temporaryDirectory, { recursive: true, force: true })
  throw error
}

after(() => rm(temporaryDirectory, { recursive: true, force: true }))
const { generateAppCode } = await import(pathToFileURL(bundledModule).href)
const encoder = new TextEncoder()

// These frames match AppController.chatToGenCode: JSON { d: chunk }, then event: done.
const chunkFrame = (content, lineEnding = '\n') =>
  `data: ${JSON.stringify({ d: content })}${lineEnding}${lineEnding}`
const doneFrame = (lineEnding = '\n') => `event: done${lineEnding}data:${lineEnding}${lineEnding}`

function responseFromParts(parts, { status = 200, contentType = 'text/event-stream' } = {}) {
  const stream = new ReadableStream({
    start(controller) {
      for (const part of parts) {
        controller.enqueue(typeof part === 'string' ? encoder.encode(part) : part)
      }
      controller.close()
    },
  })
  return new Response(stream, { status, headers: { 'Content-Type': contentType } })
}

function mockResponse(context, response) {
  return context.mock.method(globalThis, 'fetch', async () => response)
}

function collect(options = {}) {
  const chunks = []
  const promise = generateAppCode({
    appId: '923456789012345678',
    message: '创建一个中文网站 & 保留换行\n下一行',
    onChunk: (content) => chunks.push(content),
    ...options,
  })
  return { chunks, promise }
}

test('发送一次携带 Session 的 GET 请求，保留长 ID 和完整提示词', async (t) => {
  const fetchMock = mockResponse(t, responseFromParts([chunkFrame('完成') + doneFrame()]))
  const controller = new AbortController()
  const { chunks, promise } = collect({ signal: controller.signal })
  await promise

  assert.deepEqual(chunks, ['完成'])
  assert.equal(fetchMock.mock.callCount(), 1)
  const [input, options] = fetchMock.mock.calls[0].arguments
  const url = new URL(input)
  assert.equal(url.origin + url.pathname, 'https://stream.test/api/app/chat/gen/code')
  assert.equal(url.searchParams.get('appId'), '923456789012345678')
  assert.equal(url.searchParams.get('message'), '创建一个中文网站 & 保留换行\n下一行')
  assert.equal(options.method, 'GET')
  assert.equal(options.credentials, 'include')
  assert.equal(options.headers.Accept, 'text/event-stream')
  assert.equal(options.signal, controller.signal)
})

test('UTF-8 汉字和 emoji 跨字节分片仍逐字还原，不产生乱码', async (t) => {
  const bytes = encoder.encode(
    chunkFrame('你好，世界 🌏') + chunkFrame('\n继续生成 ✨') + doneFrame(),
  )
  mockResponse(t, responseFromParts(Array.from(bytes, (byte) => Uint8Array.of(byte))))
  const { chunks, promise } = collect()
  await promise
  assert.deepEqual(chunks, ['你好，世界 🌏', '\n继续生成 ✨'])
  assert.ok(!chunks.join('').includes('\uFFFD'))
})

test('CRLF 的每个换行和事件边界被拆到不同网络片段时仍正确解析', async (t) => {
  const text =
    ': heartbeat\r\n\r\n' +
    chunkFrame('第一段', '\r\n') +
    chunkFrame('第二段', '\r\n') +
    doneFrame('\r\n')
  const bytes = encoder.encode(text)
  mockResponse(t, responseFromParts(Array.from(bytes, (byte) => Uint8Array.of(byte))))
  const { chunks, promise } = collect()
  await promise
  assert.deepEqual(chunks, ['第一段', '第二段'])
})

test('同一个网络片段内的多个 chunk 依次输出，done 后的内容不会输出', async (t) => {
  mockResponse(
    t,
    responseFromParts([
      chunkFrame('A') + chunkFrame('B') + chunkFrame('C') + doneFrame() + chunkFrame('忽略'),
    ]),
  )
  const { chunks, promise } = collect()
  await promise
  assert.deepEqual(chunks, ['A', 'B', 'C'])
})

test('收到内容不代表完成，空 data 的 done 到达后才 resolve 并释放读取锁', async (t) => {
  let streamController
  let resolveChunk
  let cancelled = false
  const received = new Promise((resolve) => {
    resolveChunk = resolve
  })
  const stream = new ReadableStream({
    start(controller) {
      streamController = controller
    },
    cancel() {
      cancelled = true
    },
  })
  mockResponse(t, new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } }))
  let settled = false
  const promise = generateAppCode({
    appId: 7,
    message: '生成网站',
    onChunk: (content) => resolveChunk(content),
  }).then(() => {
    settled = true
  })

  streamController.enqueue(encoder.encode(chunkFrame('代码内容')))
  assert.equal(await received, '代码内容')
  assert.equal(settled, false)
  streamController.enqueue(encoder.encode(doneFrame()))
  await promise
  assert.equal(settled, true)
  assert.equal(cancelled, true)
  assert.equal(stream.locked, false)
})

test('未收到 done 就 EOF 时 reject，保留已收到的内容供错误展示', async (t) => {
  const response = responseFromParts([chunkFrame('只有部分内容')])
  mockResponse(t, response)
  const { chunks, promise } = collect()
  await assert.rejects(promise, /连接已中断.*生成完成/)
  assert.deepEqual(chunks, ['只有部分内容'])
  assert.equal(response.body.locked, false)
})

test('JSON 业务错误抛出服务端信息，后续内容不会继续输出', async (t) => {
  const response = responseFromParts([
    'data: {"code":40300,"message":"无权限访问该应用"}\n\n' + chunkFrame('不应显示') + doneFrame(),
  ])
  mockResponse(t, response)
  const { chunks, promise } = collect()
  await assert.rejects(promise, /无权限访问该应用/)
  assert.deepEqual(chunks, [])
  assert.equal(response.body.locked, false)
})

for (const [name, payload, reason] of [
  ['JSON', '{"message":"模型服务暂不可用"}', /模型服务暂不可用/],
  ['纯文本', '生成文件保存失败', /生成文件保存失败/],
]) {
  test(`event: error 支持${name}错误并 reject`, async (t) => {
    mockResponse(t, responseFromParts([`event: error\ndata: ${payload}\n\n` + doneFrame()]))
    const { chunks, promise } = collect()
    await assert.rejects(promise, reason)
    assert.deepEqual(chunks, [])
  })
}

test('非 SSE 的登录业务错误转为明确的登录过期提示', async (t) => {
  mockResponse(
    t,
    new Response('{"code":40100,"message":"未登录"}', {
      headers: { 'Content-Type': 'application/json' },
    }),
  )
  const { promise } = collect()
  await assert.rejects(promise, /登录已过期/)
})

test('HTTP 错误页不是 JSON 时保留 HTTP 状态信息', async (t) => {
  mockResponse(
    t,
    new Response('<h1>Bad Gateway</h1>', {
      status: 502,
      headers: { 'Content-Type': 'text/html' },
    }),
  )
  const { promise } = collect()
  await assert.rejects(promise, /HTTP 502/)
})

test('HTTP 失败响应里的 JSON 错误信息可读', async (t) => {
  mockResponse(
    t,
    new Response('{"code":50000,"message":"模型额度不足"}', {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    }),
  )
  const { promise } = collect()
  await assert.rejects(promise, /模型额度不足/)
})

for (const [payload, reason] of [
  ['{bad json', /无法解析生成结果/],
  ['{"d":null}', /生成结果格式不正确/],
  ['42', /生成结果格式不正确/],
]) {
  test(`无效流数据 ${payload} 不会当成成功`, async (t) => {
    mockResponse(t, responseFromParts([`data: ${payload}\n\n` + doneFrame()]))
    const { promise } = collect()
    await assert.rejects(promise, reason)
  })
}

test('读取期间取消请求时 reject AbortError 并释放读取锁', async (t) => {
  const controller = new AbortController()
  let streamController
  let notifyReading
  const reading = new Promise((resolve) => {
    notifyReading = resolve
  })
  const stream = new ReadableStream({
    start(value) {
      streamController = value
    },
    pull() {
      notifyReading()
    },
  })
  t.mock.method(globalThis, 'fetch', async (_input, options) => {
    options.signal.addEventListener('abort', () => streamController.error(options.signal.reason), {
      once: true,
    })
    return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } })
  })
  const { chunks, promise } = collect({ signal: controller.signal })
  const rejected = assert.rejects(promise, { name: 'AbortError' })
  await reading
  controller.abort()
  await rejected
  assert.deepEqual(chunks, [])
  assert.equal(stream.locked, false)
})

test('onChunk 中取消时，同一网络片段后续 chunk 和 done 也不能继续消费或 resolve', async (t) => {
  const controller = new AbortController()
  const response = responseFromParts([chunkFrame('第一段') + chunkFrame('不应输出') + doneFrame()])
  mockResponse(t, response)
  const chunks = []
  const promise = generateAppCode({
    appId: 7,
    message: '生成网站',
    signal: controller.signal,
    onChunk: (chunk) => {
      chunks.push(chunk)
      controller.abort()
    },
  })
  await assert.rejects(promise, { name: 'AbortError' })
  assert.deepEqual(chunks, ['第一段'])
  assert.equal(response.body.locked, false)
})

test('调用前已取消的请求立即 reject，且不会发起 fetch', async (t) => {
  const controller = new AbortController()
  controller.abort()
  const fetchMock = t.mock.method(globalThis, 'fetch', async () => {
    throw new Error('已取消的请求不应该访问网络')
  })
  const { promise } = collect({ signal: controller.signal })
  await assert.rejects(promise, { name: 'AbortError' })
  assert.equal(fetchMock.mock.callCount(), 0)
})
