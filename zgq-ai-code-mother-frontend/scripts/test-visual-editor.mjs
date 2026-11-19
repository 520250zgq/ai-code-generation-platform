/** Run with: node scripts/test-visual-editor.mjs. */
import assert from 'node:assert/strict'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { after, test } from 'node:test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { build } from 'esbuild'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))
const temporaryDirectory = await mkdtemp(join(tmpdir(), 'zgq-visual-editor-test-'))
const outfile = join(temporaryDirectory, 'visualEditor.mjs')

try {
  await build({
    absWorkingDir: projectRoot,
    entryPoints: ['src/utils/visualEditor.ts'],
    outfile,
    bundle: true,
    platform: 'node',
    format: 'esm',
    target: 'node22',
    logLevel: 'silent',
  })
} catch (error) {
  await rm(temporaryDirectory, { recursive: true, force: true })
  throw error
}

const visualEditor = await import(pathToFileURL(outfile).href)
after(() => rm(temporaryDirectory, { recursive: true, force: true }))

const selectedElement = {
  tagName: 'button',
  id: 'submit',
  className: 'btn primary',
  selector: '#submit',
  text: '提交订单',
  attributes: { type: 'submit', 'data-role': 'primary' },
  rect: { x: 10.4, y: 20.6, width: 100.2, height: 32.5 },
}

test('格式化选中元素信息块，包含标签、选择器、文本和位置', () => {
  const prompt = visualEditor.buildElementPrompt(selectedElement)

  assert.match(prompt, /【可视化编辑 - 当前选中元素】/)
  assert.match(prompt, /- 标签：button/)
  assert.match(prompt, /- id：submit/)
  assert.match(prompt, /- class：btn primary/)
  assert.match(prompt, /- CSS 选择器：#submit/)
  assert.match(prompt, /- 文本内容：提交订单/)
  assert.match(prompt, /x=10，y=21，宽=100，高=33/)
  assert.match(prompt, /type="submit"/)
  assert.match(prompt, /data-role="primary"/)
  assert.match(prompt, /保持其他元素、样式和交互不变/)
})

test('合并提示词时保留用户原始输入，并追加元素信息', () => {
  const prompt = visualEditor.composePromptWithElement('  把按钮改成深色  ', selectedElement)

  assert.ok(prompt.startsWith('把按钮改成深色\n\n【可视化编辑 - 当前选中元素】'), prompt)
  assert.match(prompt, /- CSS 选择器：#submit/)
})

test('没有选中元素时提示词保持原样', () => {
  assert.equal(visualEditor.composePromptWithElement('  修改首页标题  ', undefined), '修改首页标题')
  assert.equal(visualEditor.composePromptWithElement('修改首页标题', null), '修改首页标题')
})

test('只有选中元素、没有用户输入时也能生成完整提示词', () => {
  const prompt = visualEditor.composePromptWithElement('   ', selectedElement)

  assert.ok(prompt.startsWith('【可视化编辑 - 当前选中元素】'), prompt)
  assert.ok(!prompt.startsWith('\n'), prompt)
})

test('过长文本和属性会被截断，避免撑大提示词', () => {
  const longElement = {
    ...selectedElement,
    text: '文'.repeat(200),
    attributes: { title: '属'.repeat(200) },
  }

  const prompt = visualEditor.buildElementPrompt(longElement)

  assert.match(prompt, new RegExp(`- 文本内容：${'文'.repeat(120)}…`))
  assert.match(prompt, new RegExp(`title="${'属'.repeat(60)}…"`))
  assert.ok(!prompt.includes('文'.repeat(121)), '文本内容必须在 120 字以内')
})

test('生成 Alert 使用的紧凑元素描述', () => {
  assert.equal(visualEditor.describeSelectedElement(selectedElement), 'button#submit.btn.primary')

  const noIdElement = { ...selectedElement, id: '', className: 'card featured extra' }
  assert.equal(visualEditor.describeSelectedElement(noIdElement), 'button.card.featured')
})

test('校验 postMessage 消息结构，拒绝伪造或残缺数据', () => {
  const message = {
    type: visualEditor.VISUAL_EDITOR_MESSAGE_TYPE,
    element: selectedElement,
  }

  assert.equal(visualEditor.isVisualEditorMessage(message), true)
  assert.equal(visualEditor.isVisualEditorMessage({ ...message, type: 'OTHER' }), false)
  assert.equal(visualEditor.isVisualEditorMessage({ type: message.type }), false)
  assert.equal(
    visualEditor.isVisualEditorMessage({ ...message, element: { ...selectedElement, tagName: 1 } }),
    false,
  )
  assert.equal(
    visualEditor.isVisualEditorMessage({
      ...message,
      element: { ...selectedElement, rect: { x: 0, y: 0, width: '100', height: 10 } },
    }),
    false,
  )
  assert.equal(
    visualEditor.isVisualEditorMessage({
      ...message,
      element: { ...selectedElement, attributes: { type: 1 } },
    }),
    false,
  )
  assert.equal(visualEditor.isVisualEditorMessage(null), false)
  assert.equal(visualEditor.isVisualEditorMessage([]), false)
})

test('消息类型常量与父页面监听保持一致', () => {
  assert.equal(visualEditor.VISUAL_EDITOR_MESSAGE_TYPE, 'AI_CODE_MOTHER_ELEMENT_SELECTED')
})

test('注入脚本自包含、语法有效且不引用父页面模块变量', () => {
  const script = visualEditor.buildVisualEditorScript()

  assert.match(script, /AI_CODE_MOTHER_ELEMENT_SELECTED/)
  assert.match(script, /__AI_CODE_MOTHER_VISUAL_EDITOR__/)
  assert.match(script, /__AI_CODE_MOTHER_VISUAL_EDITOR_CLEAR_SELECTION__/)
  assert.ok(!script.includes('VISUAL_EDITOR_GLOBAL'), '运行时不能引用父页面模块常量')
  assert.ok(!script.includes('VISUAL_EDITOR_MESSAGE_TYPE'), '运行时不能引用父页面模块常量')
  assert.ok(!script.includes('VISUAL_EDITOR_CLEAR_GLOBAL'), '运行时不能引用父页面模块常量')
  // 只解析不执行：校验注入到 iframe 的脚本本身是合法 JavaScript。
  assert.doesNotThrow(() => new Function(script))
})

test('没有 iframe 时清除选中态安全返回', () => {
  assert.doesNotThrow(() => visualEditor.clearVisualEditorSelection(null))
  assert.doesNotThrow(() => visualEditor.clearVisualEditorSelection(undefined))
})
