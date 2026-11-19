import { renderCodeBlock, renderMarkdown } from '@/utils/markdown'

export type GeneratedResponse = Partial<{
  htmlCode: string
  cssCode: string
  jsCode: string
  description: string
}>

const fieldNames = new Set(['htmlCode', 'cssCode', 'jsCode', 'description'])
const fence = String.fromCharCode(96).repeat(3)
const slash = String.fromCharCode(92)

function readString(source: string, start: number) {
  let value = ''

  for (let index = start + 1; index < source.length; index += 1) {
    const character = source[index]
    if (character === '"') return { value, end: index + 1, complete: true }
    if (character !== slash) {
      value += character
      continue
    }

    const escape = source[++index]
    if (escape === undefined) return { value, end: source.length, complete: false }
    if (escape === 'u') {
      const hex = source.slice(index + 1, index + 5)
      if (hex.length < 4 || !/^[0-9a-f]{4}$/i.test(hex)) {
        return { value, end: source.length, complete: false }
      }

      const code = Number.parseInt(hex, 16)
      index += 4
      if (code >= 0xd800 && code <= 0xdbff) {
        const next = source.slice(index + 1, index + 3)
        if (next === slash + 'u') {
          const lowHex = source.slice(index + 3, index + 7)
          if (lowHex.length < 4) return { value, end: source.length, complete: false }
          if (/^[0-9a-f]{4}$/i.test(lowHex)) {
            const low = Number.parseInt(lowHex, 16)
            if (low >= 0xdc00 && low <= 0xdfff) {
              value += String.fromCodePoint(0x10000 + ((code - 0xd800) << 10) + low - 0xdc00)
              index += 6
              continue
            }
          }
        } else if (next === '' || next === slash) {
          return { value, end: source.length, complete: false }
        }
      }
      value += String.fromCharCode(code)
      continue
    }

    const escapes: Record<string, string> = {
      '"': '"',
      [slash]: slash,
      '/': '/',
      b: '\b',
      f: '\f',
      n: '\n',
      r: '\r',
      t: '\t',
    }
    if (!(escape in escapes)) return { value, end: source.length, complete: false }
    value += escapes[escape]
  }

  return { value, end: source.length, complete: false }
}

function candidateSource(raw: string): string | null {
  let source = raw.trimStart()
  if (source.startsWith(fence)) {
    const newline = source.indexOf(String.fromCharCode(10))
    if (newline < 0) return source.toLowerCase().startsWith(fence + 'json') ? '' : null
    const language = source.slice(fence.length, newline).trim().toLowerCase()
    if (language !== 'json' && language !== '') return null
    source = source.slice(newline + 1).trimStart()
    const closing = source.lastIndexOf(String.fromCharCode(10) + fence)
    if (closing >= 0 && source.slice(closing + 1 + fence.length).trim() === '') {
      source = source.slice(0, closing)
    }
  }
  return source.startsWith('{') || source === '' ? source : null
}

/** Read completed fields and the currently growing string from a model JSON response. */
export function parseGeneratedResponse(raw: string): GeneratedResponse | null {
  const source = candidateSource(raw)
  if (source === null) return null
  if (!source) return {}

  try {
    const parsed: unknown = JSON.parse(source)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const record = parsed as Record<string, unknown>
      const fields: GeneratedResponse = {}
      for (const name of fieldNames) {
        if (typeof record[name] === 'string') fields[name as keyof GeneratedResponse] = record[name]
      }
      return Object.keys(fields).length ? fields : null
    }
  } catch {
    // During SSE streaming, the JSON object is normally unfinished.
  }

  const fields: GeneratedResponse = {}
  let position = 1
  while (position < source.length) {
    while (/\s|,/.test(source[position] ?? '') && position < source.length) position += 1
    if (source[position] !== '"') break

    const key = readString(source, position)
    if (!key.complete) break
    position = key.end
    while (/\s/.test(source[position] ?? '') && position < source.length) position += 1
    if (source[position] !== ':') break
    position += 1
    while (/\s/.test(source[position] ?? '') && position < source.length) position += 1
    if (source[position] !== '"') break

    const value = readString(source, position)
    if (fieldNames.has(key.value)) {
      fields[key.value as keyof GeneratedResponse] = value.value
    }
    position = value.end
    if (!value.complete) break
  }
  return fields
}

/** Turn model JSON into a readable explanation and highlighted source files. */
export function renderGeneratedResponse(raw: string): string {
  const fields = parseGeneratedResponse(raw)
  if (fields === null) {
    if (/^\s*(?:<!doctype\s+html|<html\b)/i.test(raw)) {
      return renderCodeBlock(raw, 'html', 'index.html')
    }
    return renderMarkdown(raw)
  }

  const description = fields.description?.trim()
  let html = '<div class="generated-response">'
  html += '<div class="generated-summary"><div class="generated-summary-label">生成说明</div>'
  html += description
    ? renderMarkdown(description)
    : '<p>正在生成页面代码，完成后可在右侧查看效果。</p>'
  html += '</div>'
  if (fields.htmlCode) html += renderCodeBlock(fields.htmlCode, 'html', 'index.html')
  if (fields.cssCode) html += renderCodeBlock(fields.cssCode, 'css', 'style.css')
  if (fields.jsCode) html += renderCodeBlock(fields.jsCode, 'javascript', 'script.js')
  return html + '</div>'
}
