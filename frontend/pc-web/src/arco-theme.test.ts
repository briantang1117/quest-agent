// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { createRequire } from 'node:module'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)

describe('Arco Less品牌主题', () => {
  it('按需使用Less样式并由基准色自动生成完整品牌色阶', async () => {
    const config = await readFile(
      fileURLToPath(new URL('../vite.config.ts', import.meta.url)),
      'utf8',
    )
    expect(config).toContain("importStyle: 'less'")
    const less = require('less') as {
      render: (source: string, options: Record<string, unknown>) => Promise<{ css: string }>
    }
    const { arcoLessOptions } = await import('../theme')
    const file = require.resolve('@arco-design/web-vue/es/style/index.less')
    const { css } = await less.render(await readFile(file, 'utf8'), {
      filename: file,
      ...arcoLessOptions,
    })
    expect(css).toMatch(/--arcoblue-6:\s*8,\s*125,\s*132/)
    for (let step = 1; step <= 10; step++) expect(css).toContain(`--arcoblue-${step}:`)
    expect(css).toMatch(/--primary-6:\s*var\(--arcoblue-6\)/)
    const style = await readFile(fileURLToPath(new URL('./style.css', import.meta.url)), 'utf8')
    expect(style).not.toContain('--color-primary-')
  })
})
