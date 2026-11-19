/** Run with: node scripts/test-markdown.mjs. No backend or browser is required. */
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))
const temporaryDirectory = await mkdtemp(join(tmpdir(), 'zgq-markdown-test-'))
const bundledModule = join(temporaryDirectory, 'markdown.mjs')

try {
  await build({
    absWorkingDir: projectRoot,
    entryPoints: ['src/utils/markdown.ts'],
    outfile: bundledModule,
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node20',
    logLevel: 'silent',
  })
} catch (error) {
  await rm(temporaryDirectory, { recursive: true, force: true })
  throw error
}

after(() => rm(temporaryDirectory, { recursive: true, force: true }))
const { renderMarkdown } = await import(pathToFileURL(bundledModule).href)

function codeBlock(html) {
  const match = html.match(/<pre\b[^>]*>\s*<code\b[^>]*>([\s\S]*?)<\/code>\s*<\/pre>/i)
  assert.ok(match, 'Expected a fenced code block')
  return match[1]
}

// Remove highlighting spans and decode entities once to recover exactly what readers see.
function codeText(html) {
  const entities = {
    '&lt;': '<',
    '&gt;': '>',
    '&amp;': '&',
    '&quot;': '"',
    '&#39;': "'",
    '&#x27;': "'",
  }
  return codeBlock(html)
    .replace(/<[^>]*>/g, '')
    .replace(/&(?:lt|gt|amp|quot|#39|#x27);/g, (entity) => entities[entity])
}

function assertNoExecutableMarkup(html) {
  assert.doesNotMatch(html, /<\/?(?:script|style|iframe|object|embed|img|svg|section)\b/i)
  assert.doesNotMatch(html, /<[^>]+\s(?:on\w+|srcdoc)\s*=/i)
}

test('renders headings, emphasis, lists, quotes, tables, inline code, and line breaks', () => {
  const html = renderMarkdown(
    '# 页面说明\n\n' +
      '**加粗**与*强调*和`const value = 1`。\n下一行\n\n' +
      '- 第一项\n- 第二项\n\n' +
      '1. 步骤一\n2. 步骤二\n\n' +
      '> 引用内容\n\n' +
      '| 名称 | 状态 |\n| --- | --- |\n| 首页 | 完成 |',
  )

  assert.match(html, /<h1>页面说明<\/h1>/)
  assert.match(html, /<strong>加粗<\/strong>/)
  assert.match(html, /<em>强调<\/em>/)
  assert.match(html, /<code>const value = 1<\/code>/)
  assert.match(html, /。<br\s*\/?>\s*下一行/)
  assert.match(html, /<ul>[\s\S]*?<li>第一项<\/li>[\s\S]*?<li>第二项<\/li>[\s\S]*?<\/ul>/)
  assert.match(html, /<ol>[\s\S]*?<li>步骤一<\/li>[\s\S]*?<li>步骤二<\/li>[\s\S]*?<\/ol>/)
  assert.match(html, /<blockquote>[\s\S]*?引用内容[\s\S]*?<\/blockquote>/)
  assert.match(html, /<table>[\s\S]*?<th>名称<\/th>[\s\S]*?<td>首页<\/td>[\s\S]*?<\/table>/)
  assert.equal(renderMarkdown(''), '')
})

for (const [language, source, token] of [
  ['html', '<section class="hero">你好</section>', 'tag'],
  ['xml', '<section class="hero">你好</section>', 'tag'],
  ['css', '.hero { color: red; margin: 0; }', 'attribute'],
  ['javascript', 'const title = "你好"; console.log(title);', 'keyword'],
  ['js', 'const title = "你好"; console.log(title);', 'keyword'],
]) {
  test(`highlights ${language} while preserving the original source`, () => {
    const html = renderMarkdown('```' + language + '\n' + source + '\n```')
    assert.match(codeBlock(html), new RegExp('class="[^"]*\\bhljs-' + token + '\\b'))
    assert.equal(codeText(html), source + '\n')
    assertNoExecutableMarkup(html)
  })
}

test('escapes raw HTML, scripts, and event handlers in ordinary Markdown and inline code', () => {
  const html = renderMarkdown(
    '<script>alert("xss")</script>\n\n' +
      '<img src=x onerror="alert(1)">\n\n' +
      '<svg onload="alert(1)"></svg>\n\n' +
      '`<iframe srcdoc="<script>alert(1)</script>"></iframe>`',
  )
  assertNoExecutableMarkup(html)
  assert.match(html, /&lt;script&gt;/)
  assert.match(html, /&lt;img\b/)
  assert.match(html, /&lt;svg\b/)
  assert.match(html, /<code>&lt;iframe\b/)
})

test('uses safe targets for Markdown links and automatically detected links', () => {
  const html = renderMarkdown('[文档](https://example.com/docs?a=1&b=2)\n\nhttps://example.org')
  const links = html.match(/<a\b[^>]*>/g) ?? []
  assert.equal(links.length, 2)
  for (const link of links) {
    assert.match(link, /\btarget="_blank"/)
    const rel = link.match(/\brel="([^"]*)"/)?.[1].split(/\s+/) ?? []
    assert.ok(rel.includes('noopener'))
    assert.ok(rel.includes('noreferrer'))
  }
  assert.match(html, /href="https:\/\/example\.com\/docs\?a=1&amp;b=2"/)
  assert.match(html, /href="https:\/\/example\.org"/)
})

test('rejects executable link schemes including case and entity obfuscation', () => {
  const html = renderMarkdown(
    '[script](javascript:alert%281%29)\n\n' +
      '[mixed case](JaVaScRiPt:alert%281%29)\n\n' +
      '[entity](jav&#x61;script:alert%281%29)\n\n' +
      '[vbscript](vbscript:msgbox%281%29)\n\n' +
      '[data](data:text/html;base64,PHNjcmlwdD4=)',
  )
  assert.doesNotMatch(html, /<a\b/i)
  assertNoExecutableMarkup(html)
})

for (const language of ['unknown-language', '']) {
  test(`renders ${language || 'unlabelled'} code as escaped plain text`, () => {
    const source = '<script>alert("xss")</script>\n<div onmouseover="alert(1)">& text</div>'
    const html = renderMarkdown('```' + language + '\n' + source + '\n```')
    assert.equal(codeText(html), source + '\n')
    assert.doesNotMatch(codeBlock(html), /<span\b/)
    assertNoExecutableMarkup(html)
  })
}

test('fence language metadata cannot inject tags or event attributes', () => {
  const source = '<img src=x onerror="alert(1)"> & "text"'
  const html = renderMarkdown('```unknown"><img src=x onerror="alert(1)">\n' + source + '\n```')
  assert.equal(codeText(html), source + '\n')
  assertNoExecutableMarkup(html)
  assert.doesNotMatch(codeBlock(html), /<span\b/)
})

test('renders every streaming prefix safely, including an unfinished highlighted fence', () => {
  const opening = '## 正在生成\n\n```html\n'
  const source = '<section onclick="alert(1)">Hello <strong>世界</strong></section>\n'
  const ending = '```\n\n**生成完成**\n\n下面是说明。'
  const content = opening + source + ending

  for (let length = 0; length <= content.length; length += 1) {
    const html = renderMarkdown(content.slice(0, length))
    assert.equal(typeof html, 'string', `Prefix length ${length}`)
    assertNoExecutableMarkup(html)
    if (length >= opening.length && length <= opening.length + source.length) {
      assert.equal(
        codeText(html).replace(/\n$/, ''),
        content.slice(opening.length, length).replace(/\n$/, ''),
        `Unclosed fence at prefix length ${length} must preserve the received code`,
      )
    }
  }

  const unclosed = renderMarkdown(opening + source)
  assert.match(codeBlock(unclosed), /hljs-tag/)
  assert.equal(codeText(unclosed), source)
  assert.equal(codeText(renderMarkdown(opening + source + '``')), source + '``')

  const completed = renderMarkdown(content)
  assert.equal(codeText(completed), source)
  assert.match(completed, /<p><strong>生成完成<\/strong><\/p>/)
  assert.match(completed, /<p>下面是说明。<\/p>/)
})
