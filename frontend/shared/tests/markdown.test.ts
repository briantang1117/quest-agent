// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { renderMarkdown } from '../src/markdown'

function dom(source: string) { const div = document.createElement('div'); div.innerHTML = renderMarkdown(source); return div }

describe('safe Markdown', () => {
  it('renders headings, tables and fenced code', () => {
    const el = dom('# 接入说明\n\n| 功能 | 状态 |\n| --- | --- |\n| 题库 | 未接入 |\n\n```js\nconst a = 1\n```')
    expect(el.querySelector('h1')?.textContent).toBe('接入说明')
    expect(el.querySelector('td')?.textContent).toBe('题库')
    expect(el.querySelector('pre code')?.textContent).toContain('const a = 1')
  })
  it('renders inline and block formulas', () => {
    const el = dom('公式 $x^2$\n\n$$\n\\frac{1}{2}\n$$')
    expect(el.querySelectorAll('.katex')).toHaveLength(2)
    expect(el.querySelector('.katex-display')).not.toBeNull()
  })
  it.each([
    '<script>alert(1)</script>',
    '<img src=x onerror=alert(1)>',
    '[点击](javascript:alert%281%29)',
    '<svg onload=alert(1)><a xlink:href="javascript:alert(1)">x</a></svg>',
    '<iframe srcdoc="<script>alert(1)</script>"></iframe>',
    '$\\href{javascript:alert(1)}{x}$',
    '$\\htmlStyle{background:url(javascript:alert(1))}{x}$',
  ])('removes executable content from %s', source => {
    const el = dom(source)
    expect(el.querySelector('script,iframe,svg,object,embed')).toBeNull()
    for (const node of el.querySelectorAll('*')) for (const attr of node.attributes) {
      expect(attr.name).not.toMatch(/^on/i)
      if (/^(href|src|xlink:href)$/i.test(attr.name)) expect(attr.value).not.toMatch(/^\s*(javascript|data):/i)
    }
  })
  it('keeps safe links and images', () => {
    const el = dom('[文档](https://example.com)\n\n![题图](https://example.com/a.png)')
    expect(el.querySelector('a')?.getAttribute('href')).toBe('https://example.com')
    expect(el.querySelector('img')?.getAttribute('src')).toBe('https://example.com/a.png')
  })
  it('handles incomplete formulas and escapes HTML in code', () => {
    const el = dom('未完成 $x\n\n`<img onerror=alert(1)>`')
    expect(el.querySelector('code')?.textContent).toBe('<img onerror=alert(1)>')
    expect(el.querySelector('img')).toBeNull()
  })
})
