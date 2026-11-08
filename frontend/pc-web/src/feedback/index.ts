import { Message, Modal } from '@arco-design/web-vue'
import '@arco-design/web-vue/es/message/style/index.js'
import '@arco-design/web-vue/es/modal/style/index.js'
import { installErrorFeedback, type ErrorFeedbackRules } from '@quest/shared'

export function initializeErrorFeedback(rules: ErrorFeedbackRules = {}) {
  return installErrorFeedback((feedback) => {
    if (feedback.kind === 'dialog') {
      Modal.error({
        title: feedback.title ?? '操作未完成',
        content: feedback.message,
        okText: '知道了',
        titleAlign: 'start',
      })
    } else Message.error({ content: feedback.message, position: 'top', duration: 4000 })
  }, rules)
}
