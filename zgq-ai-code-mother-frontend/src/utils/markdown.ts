import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/core'
import css from 'highlight.js/lib/languages/css'
import javascript from 'highlight.js/lib/languages/javascript'
import xml from 'highlight.js/lib/languages/xml'

hljs.registerLanguage('html', xml)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('css', css)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('js', javascript)

function highlightCode(source: string, language: string): string {
  if (!hljs.getLanguage(language)) return ''

  try {
    return hljs.highlight(source, { language, ignoreIllegals: true }).value
  } catch {
    return ''
  }
}

const markdown = new MarkdownIt({
  html: false,
  breaks: true,
  linkify: true,
})

markdown.renderer.rules.fence = (tokens, index) => {
  const token = tokens[index]!
  const language = token.info.trim().split(/\s+/, 1)[0] ?? ''
  return renderCodeBlock(token.content, language, language || '代码')
}

markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  const token = tokens[index]!
  token.attrSet('target', '_blank')
  token.attrSet('rel', 'noopener noreferrer')
  return self.renderToken(tokens, index, options)
}

/** Render one complete or in-progress assistant reply as safe Markdown HTML. */
export function renderMarkdown(content: string): string {
  return markdown.render(content)
}

/** Render a code panel from a decoded JSON source field without Markdown fence parsing. */
export function renderCodeBlock(source: string, language: string, label: string): string {
  const highlighted = language && hljs.getLanguage(language) ? highlightCode(source, language) : ''
  const code = highlighted || markdown.utils.escapeHtml(source)
  const languageClass =
    language && hljs.getLanguage(language) ? 'language-' + language : 'language-plain'

  return (
    '<div class="code-block">' +
    '<div class="code-block-header"><span class="code-language">' +
    markdown.utils.escapeHtml(label) +
    '</span></div>' +
    '<pre><code class="hljs ' +
    markdown.utils.escapeHtml(languageClass) +
    '">' +
    code +
    '</code></pre></div>\n'
  )
}
