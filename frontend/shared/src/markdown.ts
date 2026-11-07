import MarkdownIt from 'markdown-it'
import katex from 'katex'
import DOMPurify from 'dompurify'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
function math(source: string, displayMode: boolean) {
  return katex.renderToString(source, {
    displayMode,
    throwOnError: false,
    trust: false,
    strict: 'ignore',
    output: 'html',
    maxExpand: 1000,
    maxSize: 20,
  })
}
md.inline.ruler.before('escape', 'math_inline', (state, silent) => {
  const start = state.pos
  if (state.src[start] !== '$' || state.src[start + 1] === '$') return false
  let end = start + 1
  while ((end = state.src.indexOf('$', end)) !== -1) {
    if (state.src[end - 1] !== '\\') break
    end++
  }
  if (end < 0 || end === start + 1 || state.src.slice(start, end).includes('\n')) return false
  if (!silent) {
    const token = state.push('math_inline', 'math', 0)
    token.content = state.src.slice(start + 1, end)
  }
  state.pos = end + 1
  return true
})
md.renderer.rules.math_inline = (tokens, index) => math(tokens[index]!.content, false)
md.block.ruler.before('fence', 'math_block', (state, startLine, endLine, silent) => {
  const start = state.bMarks[startLine]! + state.tShift[startLine]!
  const firstLine = state.src.slice(start, state.eMarks[startLine])
  if (!firstLine.startsWith('$$')) return false
  let content = firstLine.slice(2)
  let next = startLine
  if (content.trimEnd().endsWith('$$')) content = content.trimEnd().slice(0, -2)
  else {
    let closed = false
    while (++next < endLine) {
      const line = state.src.slice(state.bMarks[next], state.eMarks[next])
      if (line.trimEnd().endsWith('$$')) {
        content += '\n' + line.trimEnd().slice(0, -2)
        closed = true
        break
      }
      content += '\n' + line
    }
    if (!closed) return false
  }
  if (silent) return true
  const token = state.push('math_block', 'math', 0)
  token.content = content
  token.block = true
  token.map = [startLine, next + 1]
  state.line = next + 1
  return true
})
md.renderer.rules.math_block = (tokens, index) => math(tokens[index]!.content, true) + '\n'

/** Sanitize after all Markdown/KaTeX transforms; never concatenate untrusted HTML afterwards. */
export function renderMarkdown(source: string): string {
  return DOMPurify.sanitize(md.render(source), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'iframe', 'object', 'embed', 'form', 'input'],
    FORBID_ATTR: ['srcdoc'],
    ALLOW_DATA_ATTR: false,
    ADD_URI_SAFE_ATTR: [],
  })
}
