import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { ApiError, notifyError } from '@quest/shared'
import { initializeErrorFeedback } from './error-feedback'
import { initializeErrorFeedback as initializeMobileFeedback } from '../../mobile-agent/src/error-feedback'
let cleanup: (() => void) | undefined
afterEach(() => {
  cleanup?.()
  vi.restoreAllMocks()
})

describe('双端全局错误适配', () => {
  it('PC普通403只使用Arco Message，显式规则才使用Modal', () => {
    const message = vi.spyOn(Message, 'error').mockReturnValue({ close() {} })
    const modal = vi.spyOn(Modal, 'error').mockReturnValue({ close() {}, update() {} })
    cleanup = initializeErrorFeedback({ 40920: { kind: 'dialog', title: '内容更新' } })
    notifyError(new ApiError('Access denied', 40300, 'trace', 403))
    expect(message).toHaveBeenCalledWith(
      expect.objectContaining({ content: '暂无权限执行此操作，请联系管理员' }),
    )
    expect(modal).not.toHaveBeenCalled()
    notifyError(new ApiError('conflict', 40920))
    expect(modal).toHaveBeenCalledWith(
      expect.objectContaining({ title: '内容更新', okText: '知道了' }),
    )
    expect(message).toHaveBeenCalledTimes(1)
  })
  it('移动端使用Vant Toast和Dialog，不调用Arco', async () => {
    const message = vi.spyOn(Message, 'error')
    cleanup = initializeMobileFeedback({ 40920: { kind: 'dialog' } })
    notifyError(new ApiError('denied', 40300, 'trace', 403))
    notifyError(new ApiError('conflict', 40920))
    await nextTick()
    expect(document.querySelector('.van-toast')?.textContent).toContain(
      '暂无权限执行此操作，请联系管理员',
    )
    expect(document.querySelector('.van-dialog')?.textContent).toContain('知道了')
    expect(document.body.textContent).not.toContain('trace')
    expect(message).not.toHaveBeenCalled()
  })
})
