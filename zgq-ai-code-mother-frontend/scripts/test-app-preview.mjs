/** Run with: node scripts/test-app-preview.mjs. */
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))
const temporaryDirectory = await mkdtemp(join(tmpdir(), 'zgq-app-preview-test-'))

async function buildPreviewModule(name, env) {
  const outfile = join(temporaryDirectory, `${name}.mjs`)
  await build({
    absWorkingDir: projectRoot,
    entryPoints: ['src/utils/app.ts'],
    outfile,
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node22',
    alias: { '@': join(projectRoot, 'src') },
    define: { 'import.meta.env': JSON.stringify(env) },
    logLevel: 'silent',
  })
  return import(pathToFileURL(outfile).href)
}

let customPreview
let apiFallback
let blankPreviewFallback

try {
  ;[customPreview, apiFallback, blankPreviewFallback] = await Promise.all([
    buildPreviewModule('custom-preview', {
      VITE_API_BASE_URL: 'https://api.example.com/api',
      VITE_APP_PREVIEW_BASE_URL: 'https://preview.example.com/root///',
    }),
    buildPreviewModule('api-fallback', { VITE_API_BASE_URL: 'https://api.example.com/api///' }),
    buildPreviewModule('blank-preview-fallback', {
      VITE_API_BASE_URL: 'https://api.example.com/api/',
      VITE_APP_PREVIEW_BASE_URL: '   ',
    }),
  ])
} catch (error) {
  await rm(temporaryDirectory, { recursive: true, force: true })
  throw error
}

after(() => rm(temporaryDirectory, { recursive: true, force: true }))

test('使用独立预览根地址，并规范化尾部斜杠', () => {
  assert.equal(
    customPreview.getAppPreviewUrl({ id: '123', codeGenType: 'multi_file' }),
    'https://preview.example.com/root/static/multi_file_123/',
  )
})

test('未设置预览根地址时回退到 API 根地址', () => {
  assert.equal(
    apiFallback.getAppPreviewUrl({ id: '456', codeGenType: 'html' }),
    'https://api.example.com/api/static/html_456/',
  )
})

test('预览根地址为空白时回退到 API 根地址', () => {
  assert.equal(
    blankPreviewFallback.getAppPreviewUrl({ id: '789', codeGenType: 'html' }),
    'https://api.example.com/api/static/html_789/',
  )
})

test('应用 ID 或生成类型缺失时不构造预览地址', () => {
  assert.equal(customPreview.getAppPreviewUrl({ id: '', codeGenType: 'html' }), '')
  assert.equal(customPreview.getAppPreviewUrl({ id: '123', codeGenType: '' }), '')
})
