import dayjs from 'dayjs'
import { APP_PREVIEW_BASE_URL } from '@/config'

/** 以字符串比较两个 id，兼容后端 Long 序列化后的不同运行时类型。 */
export function isSameId(left: unknown, right: unknown): boolean {
  return left != null && right != null && String(left) === String(right)
}

/** 生成类型的展示信息；shortLabel 用于标题等紧凑位置，fullLabel 用于详情。 */
export type CodeGenTypeMeta = {
  key: 'html' | 'multi_file' | 'vue_project' | 'unknown'
  shortLabel: string
  fullLabel: string
  description: string
}

const CODE_GEN_TYPE_META: Record<string, CodeGenTypeMeta> = {
  html: {
    key: 'html',
    shortLabel: 'HTML',
    fullLabel: '原生 HTML 模式',
    description: '单文件 HTML 静态页面，适合简单展示场景',
  },
  multi_file: {
    key: 'multi_file',
    shortLabel: '多文件',
    fullLabel: '原生多文件模式',
    description: 'HTML、CSS、JavaScript 分离的多文件静态网站',
  },
  vue_project: {
    key: 'vue_project',
    shortLabel: 'Vue 工程',
    fullLabel: 'Vue 工程模式',
    description: '工程化 Vue 项目，适合复杂交互和多页面应用',
  },
}

const UNKNOWN_CODE_GEN_TYPE: CodeGenTypeMeta = {
  key: 'unknown',
  shortLabel: '未知类型',
  fullLabel: '未知生成类型',
  description: '服务端未返回可识别的代码生成类型',
}

/** 按后端枚举 value 取得生成类型的展示文案，未知值使用统一兜底。 */
export function getCodeGenTypeMeta(type?: string): CodeGenTypeMeta {
  return (type && CODE_GEN_TYPE_META[type]) || UNKNOWN_CODE_GEN_TYPE
}

/** 根据生成类型和应用 id 拼接生成站点预览地址。 */
export function getAppPreviewUrl(app: Pick<API.AppVO, 'id' | 'codeGenType'>): string {
  if (!app.id || !app.codeGenType) return ''
  return `${APP_PREVIEW_BASE_URL}/static/${encodeURIComponent(`${app.codeGenType}_${app.id}`)}/`
}

/** 格式化后端时间字段，非法或缺失时返回调用方给出的兜底文案。 */
export function formatDateTime(time?: string, fallback = '—', includeSeconds = true): string {
  if (!time || !dayjs(time).isValid()) return fallback
  return dayjs(time).format(`YYYY-MM-DD HH:mm${includeSeconds ? ':ss' : ''}`)
}

/** 以不带秒的格式展示应用时间，适合卡片等紧凑位置。 */
export function formatAppTime(time?: string): string {
  return formatDateTime(time, '时间未知', false)
}

/** 从任意异常中提取可展示的提示，无法识别时使用兜底文案。 */
export function getErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) return error.message
  return fallback
}

/** 禁止部署地址和用户填写的封面链接使用 javascript: 等可执行协议。 */
export function toSafeHttpUrl(value?: string): string {
  if (!value) return ''
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? url.href : ''
  } catch {
    return ''
  }
}

/** 后端 Long 返回字符串；只校验，不转 Number，避免雪花 ID 丢失精度。 */
export function isValidAppId(value: unknown): boolean {
  const id = String(value ?? '')
  return /^[1-9]\d{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n
}
