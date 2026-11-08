// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { createRequire } from 'node:module'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const require = createRequire(import.meta.url)

describe('Arco Less品牌主题', () => {
  it('后台纸张卡片圆角不被Arco默认直角覆盖，品牌宋体走在线与系统降级', async () => {
    const layout = await readFile(
      fileURLToPath(new URL('../layouts/admin-layout.css', import.meta.url)),
      'utf8',
    )
    expect(layout).toMatch(
      /\.admin-shell \.admin-users-card\.arco-card\s*\{[^}]*border-radius:\s*2px/,
    )
    expect(layout).not.toContain('!important')
    const fonts = await readFile(require.resolve('@quest/shared/fonts.css'), 'utf8')
    expect(fonts).toContain('https://fonts.googleapis.com/css2?')
    expect(fonts).toContain("'Noto Serif SC'")
    expect(fonts).toContain("'Songti SC'")
    expect(fonts).toContain("'SimSun'")
    expect(fonts).not.toContain('@font-face')
  })
  it('默认正文通过在线思源黑体与平台字体降级，组件库也使用同一字体', async () => {
    const fonts = await readFile(require.resolve('@quest/shared/fonts.css'), 'utf8')
    expect(fonts).toContain('family=Noto+Sans+SC:wght@100..900')
    expect(fonts).toMatch(
      /--font-sans:\s*'Noto Sans SC',\s*'Noto Sans',\s*'PingFang SC',\s*'Microsoft YaHei',\s*system-ui,\s*sans-serif/,
    )
    expect(fonts).toContain('--van-base-font: var(--font-sans)')
    const { arcoLessOptions } = await import('../../theme')
    expect(arcoLessOptions.modifyVars).toHaveProperty('font-family', 'var(--font-sans)')
  })
  it('按需使用Less样式并由基准色自动生成完整品牌色阶', async () => {
    const config = await readFile(
      fileURLToPath(new URL('../../vite.config.ts', import.meta.url)),
      'utf8',
    )
    expect(config).toContain("importStyle: 'less'")
    const less = require('less') as {
      render: (source: string, options: Record<string, unknown>) => Promise<{ css: string }>
    }
    const { arcoLessOptions } = await import('../../theme')
    const file = require.resolve('@arco-design/web-vue/es/style/index.less')
    const { css } = await less.render(await readFile(file, 'utf8'), {
      filename: file,
      ...arcoLessOptions,
    })
    expect(css).toMatch(/--arcoblue-6:\s*8,\s*125,\s*132/)
    for (let step = 1; step <= 10; step++) expect(css).toContain(`--arcoblue-${step}:`)
    expect(css).toMatch(/--primary-6:\s*var\(--arcoblue-6\)/)
    const style = await readFile(fileURLToPath(new URL('../style.css', import.meta.url)), 'utf8')
    expect(style).not.toContain('--color-primary-')
    expect(style).toMatch(/\.login-page \.auth-panel\s*\{[^}]*border-radius:\s*2px/)
  })
})
