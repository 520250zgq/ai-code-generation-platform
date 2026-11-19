/**
 * 可视化编辑（iframe 通信）工具。
 *
 * <p>预览站点通过 iframe 嵌入工作台。进入编辑模式时，父页面向同源的 iframe 注入一段
 * 元素选择脚本：脚本负责悬浮高亮、点击拦截和元素信息采集，再通过
 * {@code window.parent.postMessage} 把选中的元素信息回传父页面。</p>
 *
 * <p>为什么必须同源：父页面只有访问同源 iframe 的 {@code contentDocument} 才能动态注入脚本；
 * 跨域时浏览器会直接拒绝。本地通过 Vite 代理、线上通过 Nginx 把前后端部署到同一域名，
 * 就能同时满足 API 请求和 iframe 注入的需要。</p>
 *
 * <p>本文件只处理 iframe 侧协议与 DOM 注入，不依赖 Vue，页面只需要调用
 * {@link mountVisualEditor} 和 {@link composePromptWithElement}。</p>
 */

/** 子页面向父页面发送选中元素消息时使用的类型。 */
export const VISUAL_EDITOR_MESSAGE_TYPE = 'AI_CODE_MOTHER_ELEMENT_SELECTED'

/** 注入到 iframe 的样式元素 id，重复注入时用于清理旧样式。 */
const VISUAL_EDITOR_STYLE_ID = 'ai-code-mother-visual-editor-style'

/** 注入到 iframe 的脚本元素 id，重复注入时用于清理旧脚本。 */
const VISUAL_EDITOR_SCRIPT_ID = 'ai-code-mother-visual-editor-script'

/** 注入脚本挂在 iframe window 上的清理函数名。 */
const VISUAL_EDITOR_GLOBAL = '__AI_CODE_MOTHER_VISUAL_EDITOR__'

/** 注入脚本挂在 iframe window 上的“清除选中态”函数名。 */
const VISUAL_EDITOR_CLEAR_GLOBAL = '__AI_CODE_MOTHER_VISUAL_EDITOR_CLEAR_SELECTION__'

/** 元素在页面中的位置和尺寸，单位为像素。 */
export type SelectedElementRect = {
  x: number
  y: number
  width: number
  height: number
}

/** 子页面上报的选中元素信息。 */
export type SelectedElementInfo = {
  /** 小写标签名，例如 button、div。 */
  tagName: string
  /** 元素 id，没有时为空字符串。 */
  id: string
  /** 元素 class 原始字符串，没有时为空字符串。 */
  className: string
  /** 由父级到自身的 CSS 选择器。 */
  selector: string
  /** 截断后的文本内容。 */
  text: string
  /** 元素属性键值对，已截断超长值。 */
  attributes: Record<string, string>
  /** 元素位置和尺寸。 */
  rect: SelectedElementRect
}

/** postMessage 的消息体。 */
export type VisualEditorMessage = {
  type: typeof VISUAL_EDITOR_MESSAGE_TYPE
  element: SelectedElementInfo
}

/** iframe 注入脚本保存在 window 上的清理函数。 */
type VisualEditorWindow = Window & {
  [VISUAL_EDITOR_GLOBAL]?: () => void
}

/** 可视化编辑不可用时抛出的可读异常，页面应展示 message。 */
export class VisualEditorError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'VisualEditorError'
  }
}

/** 已挂载编辑器的 iframe，避免同一 iframe 重复注册 message 监听。 */
const mountedEditors = new WeakMap<HTMLIFrameElement, () => void>()

/** 注入到 iframe 的样式：悬浮虚线边框、选中实线边框和十字光标。 */
const VISUAL_EDITOR_STYLE = `
  html[data-ai-code-mother-editing='true'],
  html[data-ai-code-mother-editing='true'] * {
    cursor: crosshair !important;
  }
  [data-ai-code-mother-hover='true'] {
    outline: 2px dashed #16b99a !important;
    outline-offset: 1px !important;
  }
  [data-ai-code-mother-selected='true'] {
    outline: 2px solid #0b7a63 !important;
    outline-offset: 1px !important;
    box-shadow: 0 0 0 4px rgba(22, 185, 154, 0.2) !important;
  }
`

/**
 * 判断 postMessage 数据是否是可视化编辑的选中元素消息。
 *
 * <p>父页面必须校验消息来源和结构，不能把任意 window 发来的数据直接展示或拼进提示词。</p>
 */
export function isVisualEditorMessage(value: unknown): value is VisualEditorMessage {
  if (!isRecord(value) || value.type !== VISUAL_EDITOR_MESSAGE_TYPE) {
    return false
  }
  const element = value.element
  if (!isRecord(element)) {
    return false
  }
  if (
    typeof element.tagName !== 'string' ||
    typeof element.id !== 'string' ||
    typeof element.className !== 'string' ||
    typeof element.selector !== 'string' ||
    typeof element.text !== 'string'
  ) {
    return false
  }
  if (!isRecord(element.attributes) || !isRecord(element.rect)) {
    return false
  }
  if (!Object.values(element.attributes).every((attribute) => typeof attribute === 'string')) {
    return false
  }
  const rect = element.rect
  return (['x', 'y', 'width', 'height'] as const).every(
    (key) => typeof rect[key] === 'number' && Number.isFinite(rect[key]),
  )
}

/**
 * 生成展示用的元素描述，例如 {@code button#submit.btn.primary}。
 */
export function describeSelectedElement(element: SelectedElementInfo): string {
  const classes = element.className
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((name) => `.${name}`)
    .join('')
  const description = `${element.tagName}${element.id ? `#${element.id}` : ''}${classes}`
  return description.length > 80 ? `${description.slice(0, 80)}…` : description
}

/**
 * 把选中元素格式化成提示词信息块。
 *
 * <p>格式与后端 HTML / 多文件 / Vue 三套系统提示词中的约定保持一致，
 * 修改提示词时需要同步更新这里。</p>
 */
export function buildElementPrompt(element: SelectedElementInfo): string {
  const text = truncate(element.text, 120)
  const attributes = Object.entries(element.attributes)
    .slice(0, 10)
    .map(([name, value]) => `${name}="${truncate(value, 60)}"`)
    .join('，')
  const lines = [
    '【可视化编辑 - 当前选中元素】',
    `- 标签：${element.tagName}`,
    `- id：${element.id || '无'}`,
    `- class：${element.className || '无'}`,
    `- CSS 选择器：${element.selector || '无'}`,
    `- 文本内容：${text || '无'}`,
    `- 位置：x=${Math.round(element.rect.x)}，y=${Math.round(element.rect.y)}，宽=${Math.round(
      element.rect.width,
    )}，高=${Math.round(element.rect.height)}（单位：像素）`,
  ]
  if (attributes) {
    lines.push(`- 属性：${attributes}`)
  }
  lines.push('', '请根据上面的要求修改该选中元素，并保持其他元素、样式和交互不变。')
  return lines.join('\n')
}

/**
 * 把用户输入和选中元素信息合并成最终发送给后端的提示词。
 *
 * <p>没有选中元素时保持原始输入不变，便于复用同一套发送逻辑。</p>
 */
export function composePromptWithElement(
  prompt: string,
  element?: SelectedElementInfo | null,
): string {
  const base = prompt.trim()
  if (!element) {
    return base
  }
  const elementPrompt = buildElementPrompt(element)
  return base ? `${base}\n\n${elementPrompt}` : elementPrompt
}

/**
 * 生成注入 iframe 的脚本源码。
 *
 * <p>运行时函数被 {@code toString()} 后脱离模块作用域执行，因此消息类型和全局函数名必须
 * 作为调用参数传入；函数体内不能出现本模块的常量、工具函数或导入。</p>
 */
export function buildVisualEditorScript(): string {
  return `(${installVisualEditorRuntime.toString()})(${JSON.stringify(
    VISUAL_EDITOR_MESSAGE_TYPE,
  )}, ${JSON.stringify(VISUAL_EDITOR_GLOBAL)}, ${JSON.stringify(VISUAL_EDITOR_CLEAR_GLOBAL)});`
}

/**
 * 清除 iframe 中当前元素的选中边框。
 *
 * <p>用户在 Alert 中主动移除元素信息时调用，避免提示词已清空但页面仍显示选中态。</p>
 */
export function clearVisualEditorSelection(iframe?: HTMLIFrameElement | null): void {
  if (!iframe) {
    return
  }
  try {
    const frameWindow = iframe.contentWindow as (Window & Record<string, unknown>) | null
    const clearSelection = frameWindow?.[VISUAL_EDITOR_CLEAR_GLOBAL]
    if (typeof clearSelection === 'function') {
      ;(clearSelection as () => void)()
    }
  } catch {
    // iframe 已刷新或卸载时忽略，选中信息本身已由页面状态清除。
  }
}

/**
 * 进入可视化编辑模式：向 iframe 注入选择脚本，并监听子页面回传的选中元素。
 *
 * @param iframe     同源的预览 iframe
 * @param onSelected 用户点击元素后的回调
 * @returns 退出编辑模式的清理函数
 * @throws VisualEditorError 预览尚未加载或与父页面不同源时抛出
 */
export function mountVisualEditor(
  iframe: HTMLIFrameElement,
  onSelected: (element: SelectedElementInfo) => void,
): () => void {
  if (!iframe) {
    throw new VisualEditorError('预览尚未加载完成，请稍后再试')
  }
  const frameWindow = iframe.contentWindow
  if (!frameWindow) {
    throw new VisualEditorError('预览尚未加载完成，请稍后再试')
  }
  const frameDocument = resolveSameOriginDocument(iframe)

  // 同一个 iframe 重复进入编辑模式时，先清理上一轮注入和监听。
  unmountVisualEditor(iframe)

  const styleElement = frameDocument.createElement('style')
  styleElement.id = VISUAL_EDITOR_STYLE_ID
  styleElement.textContent = VISUAL_EDITOR_STYLE
  appendToFrame(frameDocument, styleElement)

  const scriptElement = frameDocument.createElement('script')
  scriptElement.id = VISUAL_EDITOR_SCRIPT_ID
  // 注入自包含的运行时函数：脚本在 iframe 作用域内执行，才能操作子页面 DOM 并调用 parent.postMessage。
  scriptElement.textContent = buildVisualEditorScript()
  appendToFrame(frameDocument, scriptElement)

  const handleMessage = (event: MessageEvent) => {
    // 只接受当前 iframe 发来的同源消息，避免其他窗口伪造选中元素。
    if (event.source !== frameWindow || event.origin !== window.location.origin) {
      return
    }
    if (!isVisualEditorMessage(event.data)) {
      return
    }
    onSelected(event.data.element)
  }
  window.addEventListener('message', handleMessage)

  let cleaned = false
  const cleanup = () => {
    if (cleaned) {
      return
    }
    cleaned = true
    window.removeEventListener('message', handleMessage)
    try {
      ;(frameWindow as VisualEditorWindow)[VISUAL_EDITOR_GLOBAL]?.()
    } catch {
      // iframe 可能已经刷新或卸载，旧 window 上的清理函数失效时忽略即可。
    }
    styleElement.remove()
    scriptElement.remove()
    mountedEditors.delete(iframe)
  }
  mountedEditors.set(iframe, cleanup)
  return cleanup
}

/**
 * 退出指定 iframe 的可视化编辑模式；未挂载时安全返回。
 */
export function unmountVisualEditor(iframe: HTMLIFrameElement): void {
  mountedEditors.get(iframe)?.()
}

/**
 * 获取同源 iframe 的 document。
 *
 * <p>跨域时 {@code contentDocument} 返回 null 或抛出 SecurityError，这里统一转换成
 * 用户能看懂的提示，页面据此给出“请配置代理/Nginx”的告警。</p>
 */
function resolveSameOriginDocument(iframe: HTMLIFrameElement): Document {
  let frameDocument: Document | null = null
  try {
    frameDocument = iframe.contentDocument
  } catch {
    frameDocument = null
  }
  if (!frameDocument) {
    throw new VisualEditorError(
      '预览站点与当前页面不同源，无法开启可视化编辑。请使用 Vite 代理（本地）或 Nginx（线上）让前后端保持同域名。',
    )
  }
  if (!frameDocument.documentElement) {
    throw new VisualEditorError('预览页面尚未加载完成，请稍后再试')
  }
  return frameDocument
}

/**
 * 把节点追加到 iframe 的 head；部分生成页面没有 head 时退回 documentElement。
 */
function appendToFrame(frameDocument: Document, element: HTMLElement): void {
  const container = frameDocument.head || frameDocument.documentElement
  if (!container) {
    throw new VisualEditorError('预览页面结构异常，无法注入编辑脚本')
  }
  container.appendChild(element)
}

/**
 * 判断值是否为普通对象，用于校验 postMessage 数据。
 */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/**
 * 截断过长文本，避免提示词和弹窗被超长内容撑开。
 */
function truncate(value: string, maxLength: number): string {
  if (!value) {
    return ''
  }
  const normalized = value.replace(/\s+/g, ' ').trim()
  return normalized.length > maxLength ? `${normalized.slice(0, maxLength)}…` : normalized
}

/**
 * 注入到 iframe 的运行时脚本。
 *
 * <p>该函数会被 {@code toString()} 后作为 script 文本注入子页面，因此必须保持自包含：
 * 不能引用本文件的其他函数和常量，也不能依赖 TypeScript 运行时语法（类型会在构建时被擦除）。
 * 使用 ES5 写法是为了兼容生成站点可能引入的旧浏览器环境。</p>
 *
 * @param messageType     与父页面约定的消息类型
 * @param globalName      挂在 iframe window 上的清理函数名，用于重复注入时先撤销旧监听
 * @param clearGlobalName 挂在 iframe window 上的清除选中态函数名
 */
function installVisualEditorRuntime(
  messageType: string,
  globalName: string,
  clearGlobalName: string,
): void {
  const win = window as unknown as Record<string, unknown> & {
    CSS?: { escape?: (value: string) => string }
  }
  // 重复注入时先撤掉上一轮监听，避免一次点击发送多条消息。
  if (typeof win[globalName] === 'function') {
    ;(win[globalName] as () => void)()
  }

  let hovered: Element | null = null
  let selected: Element | null = null

  function isElement(node: EventTarget | null): node is Element {
    return !!(node && (node as Node).nodeType === 1)
  }

  function clearHover(): void {
    if (hovered) {
      hovered.removeAttribute('data-ai-code-mother-hover')
    }
    hovered = null
  }

  function clearSelected(): void {
    if (selected) {
      selected.removeAttribute('data-ai-code-mother-selected')
    }
    selected = null
  }

  function escapeSelector(value: string): string {
    if (win.CSS && typeof win.CSS.escape === 'function') {
      return win.CSS.escape(value)
    }
    return String(value).replace(/[^a-zA-Z0-9_-]/g, function (char) {
      return '\\' + char
    })
  }

  function buildSelector(element: Element): string {
    if (element.id) {
      return '#' + escapeSelector(element.id)
    }
    const segments: string[] = []
    let current: Element | null = element
    while (current && current !== document.documentElement) {
      const currentElement: Element = current
      let tag = currentElement.tagName.toLowerCase()
      const parent = currentElement.parentElement
      if (!parent) {
        segments.unshift(tag)
        break
      }
      const sameTagElements: Element[] = Array.prototype.slice
        .call(parent.children)
        .filter(function (child: Element) {
          return child.tagName === currentElement.tagName
        })
      if (sameTagElements.length > 1) {
        tag += ':nth-of-type(' + (sameTagElements.indexOf(currentElement) + 1) + ')'
      }
      segments.unshift(tag)
      if (currentElement === document.body) {
        break
      }
      current = parent
    }
    return segments.join(' > ')
  }

  function collectAttributes(element: Element): Record<string, string> {
    const attributes: Record<string, string> = {}
    const max = Math.min(element.attributes.length, 12)
    for (let index = 0; index < max; index++) {
      const attribute = element.attributes.item(index)
      if (!attribute || attribute.name.indexOf('data-ai-code-mother') === 0) {
        continue
      }
      const value = attribute.value || ''
      attributes[attribute.name] = value.length > 80 ? value.slice(0, 80) + '…' : value
    }
    return attributes
  }

  function collectText(element: Element): string {
    const text = (element.textContent || '').replace(/\s+/g, ' ').trim()
    return text.length > 120 ? text.slice(0, 120) + '…' : text
  }

  function collectInfo(element: Element): Record<string, unknown> {
    const rect = element.getBoundingClientRect()
    return {
      tagName: element.tagName.toLowerCase(),
      id: element.id || '',
      className: typeof element.className === 'string' ? element.className : '',
      selector: buildSelector(element),
      text: collectText(element),
      attributes: collectAttributes(element),
      rect: {
        x: Math.round(rect.x),
        y: Math.round(rect.y),
        width: Math.round(rect.width),
        height: Math.round(rect.height),
      },
    }
  }

  function handleMouseOver(event: Event): void {
    const target = event.target
    if (!isElement(target) || target === document.documentElement || target === document.body) {
      return
    }
    if (hovered === target) {
      return
    }
    clearHover()
    hovered = target
    target.setAttribute('data-ai-code-mother-hover', 'true')
  }

  function handleMouseOut(event: Event): void {
    if (!hovered) {
      return
    }
    const relatedTarget = (event as MouseEvent).relatedTarget
    if (relatedTarget && hovered.contains(relatedTarget as Node)) {
      return
    }
    clearHover()
  }

  function handleClick(event: Event): void {
    const target = event.target
    if (!isElement(target)) {
      return
    }
    // 点击页面空白区域时会命中 body/html，没有具体的可编辑元素，直接忽略。
    if (target === document.documentElement || target === document.body) {
      return
    }
    // 编辑模式下阻止链接跳转、表单提交和组件自身的事件，否则预览页面会在选中后离开当前页面。
    event.preventDefault()
    event.stopPropagation()
    event.stopImmediatePropagation()
    clearHover()
    clearSelected()
    selected = target
    target.setAttribute('data-ai-code-mother-selected', 'true')
    window.parent.postMessage(
      { type: messageType, element: collectInfo(target) },
      window.location.origin,
    )
  }

  function handleKeyDown(event: Event): void {
    if ((event as KeyboardEvent).key === 'Escape') {
      clearHover()
      clearSelected()
    }
  }

  function cleanup(): void {
    document.removeEventListener('mouseover', handleMouseOver, true)
    document.removeEventListener('mouseout', handleMouseOut, true)
    document.removeEventListener('click', handleClick, true)
    document.removeEventListener('keydown', handleKeyDown, true)
    clearHover()
    clearSelected()
    document.documentElement.removeAttribute('data-ai-code-mother-editing')
    try {
      delete win[globalName]
      delete win[clearGlobalName]
    } catch {
      win[globalName] = undefined
      win[clearGlobalName] = undefined
    }
  }

  win[globalName] = cleanup
  win[clearGlobalName] = clearSelected
  document.documentElement.setAttribute('data-ai-code-mother-editing', 'true')
  document.addEventListener('mouseover', handleMouseOver, true)
  document.addEventListener('mouseout', handleMouseOut, true)
  document.addEventListener('click', handleClick, true)
  document.addEventListener('keydown', handleKeyDown, true)
}
