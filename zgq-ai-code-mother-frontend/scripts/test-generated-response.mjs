/** Run with: node scripts/test-generated-response.mjs. No backend or browser is required. */
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))
const temporaryDirectory = await mkdtemp(join(tmpdir(), 'zgq-generated-response-test-'))
const bundledModule = join(temporaryDirectory, 'generated-response.mjs')

try {
  await build({
    absWorkingDir: projectRoot,
    entryPoints: ['src/utils/generatedResponse.ts'],
    outfile: bundledModule,
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node22',
    alias: { '@': join(projectRoot, 'src') },
    logLevel: 'silent',
  })
} catch (error) {
  await rm(temporaryDirectory, { recursive: true, force: true })
  throw error
}

after(() => rm(temporaryDirectory, { recursive: true, force: true }))
const { parseGeneratedResponse, renderGeneratedResponse } = await import(
  pathToFileURL(bundledModule).href
)

function assertSafeHtml(html) {
  assert.doesNotMatch(html, /<\/?(?:script|style|iframe|object|embed|img|svg)\b/i)
  assert.doesNotMatch(html, /<[^>]+\s(?:on\w+|srcdoc)\s*=/i)
  assert.doesNotMatch(html, /href\s*=\s*["']?javascript:/i)
}

test('extracts a complete single-file response without JSON syntax', () => {
  const htmlCode = '<!DOCTYPE html>\n<html lang="zh-CN">\n<body>你好</body>\n</html>'
  const raw = JSON.stringify({ htmlCode, description: '创建一个中文页面' })

  assert.deepEqual(parseGeneratedResponse(raw), { htmlCode, description: '创建一个中文页面' })
  const html = renderGeneratedResponse(raw)
  assert.match(html, /index\.html/)
  assert.match(html, /language-html/)
  assert.match(html, /hljs-tag/)
  assert.match(html, /创建一个中文页面/)
  assert.doesNotMatch(html, /"htmlCode"\s*:/)
  assert.doesNotMatch(html, /\\n<html/)
  assertSafeHtml(html)
})

test('extracts all three files regardless of JSON property order', () => {
  const htmlCode = '<main class="hero">欢迎</main>'
  const cssCode = '.hero { color: red; }'
  const jsCode = 'const title = "欢迎";\nconsole.log(title)'
  const description = '首页包含交互'
  const raw = JSON.stringify({ description, jsCode, cssCode, htmlCode })

  assert.deepEqual(parseGeneratedResponse(raw), { htmlCode, cssCode, jsCode, description })
  const html = renderGeneratedResponse(raw)
  for (const filename of ['index.html', 'style.css', 'script.js']) {
    assert.match(html, new RegExp(filename.replace('.', '\\.')))
  }
  assert.ok(html.indexOf('index.html') < html.indexOf('style.css'))
  assert.ok(html.indexOf('style.css') < html.indexOf('script.js'))
  assert.match(html, /language-html/)
  assert.match(html, /language-css/)
  assert.match(html, /language-(?:javascript|js)/)
  assert.match(html, /hljs-tag/)
  assert.match(html, /hljs-attribute/)
  assert.match(html, /hljs-keyword/)
  assert.match(html, /首页包含交互/)
  assert.doesNotMatch(html, /"(?:htmlCode|cssCode|jsCode|description)"\s*:/)
  assertSafeHtml(html)
})

test('shows a code field before the JSON response and its string are closed', () => {
  const raw = '{"htmlCode":"<main class=\\"hero\\">正在生成'
  assert.deepEqual(parseGeneratedResponse(raw), { htmlCode: '<main class="hero">正在生成' })
  const html = renderGeneratedResponse(raw)
  assert.match(html, /index\.html/)
  assert.match(html, /language-html/)
  assert.doesNotMatch(html, /"htmlCode"\s*:/)
  assertSafeHtml(html)
})

test('parses stable values throughout a character-by-character stream', () => {
  const htmlCode = '<section class="hero">中文</section>\n'
  const description = '已完成'
  const raw = JSON.stringify({ htmlCode, description })
  const valueStart = raw.indexOf('"htmlCode":"') + '"htmlCode":"'.length

  for (let i = 0; i <= raw.length; i += 1) {
    const prefix = raw.slice(0, i)
    const parsed = parseGeneratedResponse(prefix)
    assert.ok(parsed === null || typeof parsed === 'object', 'prefix ' + i)
    const rendered = renderGeneratedResponse(prefix)
    assert.equal(typeof rendered, 'string', 'rendered prefix ' + i)
    assertSafeHtml(rendered)
    if (i > valueStart) {
      assert.ok(parsed !== null, 'code should appear while streaming at prefix ' + i)
      assert.doesNotMatch(rendered, /"htmlCode"\s*:/)
    }
  }

  assert.deepEqual(parseGeneratedResponse(raw), { htmlCode, description })
})

test('decodes escapes split between chunks and defers incomplete escapes', () => {
  const start = '{"htmlCode":"<div class=\\'
  assert.deepEqual(parseGeneratedResponse(start), { htmlCode: '<div class=' })
  assert.deepEqual(parseGeneratedResponse(start + '"'), { htmlCode: '<div class="' })

  const slash = '{"jsCode":"const path = \\'
  assert.deepEqual(parseGeneratedResponse(slash), { jsCode: 'const path = ' })
  assert.deepEqual(parseGeneratedResponse(slash + '\\'), { jsCode: 'const path = \\' })

  const escapedSolidus = '{"htmlCode":"<\\'
  assert.deepEqual(parseGeneratedResponse(escapedSolidus), { htmlCode: '<' })
  assert.deepEqual(parseGeneratedResponse(escapedSolidus + '/'), { htmlCode: '</' })

  const unicode = '{"description":"\\u4E'
  assert.deepEqual(parseGeneratedResponse(unicode), { description: '' })
  assert.deepEqual(parseGeneratedResponse(unicode + '2D'), { description: '中' })

  const raw = '{"htmlCode":"<div class=\\"hero\\">A\\\\B\\/C\\u4E2D\\n</div>","description":"完成"}'
  assert.deepEqual(parseGeneratedResponse(raw), {
    htmlCode: '<div class="hero">A\\B/C中\n</div>',
    description: '完成',
  })
})

test('handles a partial property before the next file begins', () => {
  const raw =
    '{"description":"正在生成","cssCode":".hero { color: red; }","jsCode":"const ready = true'
  assert.deepEqual(parseGeneratedResponse(raw), {
    description: '正在生成',
    cssCode: '.hero { color: red; }',
    jsCode: 'const ready = true',
  })
  const html = renderGeneratedResponse(raw)
  assert.match(html, /style\.css/)
  assert.match(html, /script\.js/)
  assert.match(html, /正在生成/)
  assertSafeHtml(html)
})

test('keeps ordinary Markdown and code fences out of JSON extraction', () => {
  const samples = [
    '# 实现说明\n\n这里是 **普通说明**。',
    '代码对象示例： {"htmlCode":"<p>不是完整响应</p>"}',
    '```html\n<div class="hero">欢迎</div>\n```',
    '{"code":40300,"message":"无权限访问该应用"}',
  ]

  for (const raw of samples) {
    assert.equal(parseGeneratedResponse(raw), null, raw)
  }
  assert.match(renderGeneratedResponse(samples[0]), /<h1>实现说明<\/h1>/)
  assert.match(renderGeneratedResponse(samples[2]), /language-html/)
})

test('escapes untrusted code and description instead of executing markup', () => {
  const raw = JSON.stringify({
    htmlCode: '<script>alert(1)</script><img src=x onerror="alert(1)">',
    cssCode: '.x::before { content: "<img>"; }',
    jsCode: 'const html = "<svg onload=alert(1)>"',
    description: '<img src=x onerror=alert(1)> **安全说明**',
  })
  const html = renderGeneratedResponse(raw)
  assertSafeHtml(html)
  assert.match(html, /安全说明/)
  assert.match(html, /hljs-tag/)
  assert.match(html, /hljs-attribute/)
  assert.match(html, /hljs-keyword/)
  assert.doesNotMatch(html, /"(?:htmlCode|cssCode|jsCode|description)"\s*:/)
})

test('unwraps an optional JSON fence around a model response', () => {
  const fields = { htmlCode: '<h1>欢迎</h1>', description: '页面说明' }
  const marker = String.fromCharCode(96).repeat(3)
  const raw = [marker + 'json', JSON.stringify(fields), marker].join(String.fromCharCode(10))
  assert.deepEqual(parseGeneratedResponse(raw), fields)
  const html = renderGeneratedResponse(raw)
  assert.ok(html.includes('index.html'))
  assert.ok(!html.includes('"htmlCode":'))
  assertSafeHtml(html)
})

test('holds an incomplete surrogate pair until the next streamed escape arrives', () => {
  const slash = String.fromCharCode(92)
  const first = '{"description":"' + slash + 'uD83C'
  assert.deepEqual(parseGeneratedResponse(first), { description: '' })
  assert.deepEqual(parseGeneratedResponse(first + slash + 'uDF'), { description: '' })
  assert.deepEqual(parseGeneratedResponse(first + slash + 'uDFD6"}'), {
    description: String.fromCodePoint(0x1f3d6),
  })
})
