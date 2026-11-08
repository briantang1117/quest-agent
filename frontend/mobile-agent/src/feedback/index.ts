import { showDialog, showFailToast } from 'vant'
import 'vant/es/toast/index.css'
import 'vant/es/dialog/index.css'
import { installErrorFeedback, type ErrorFeedbackRules } from '@quest/shared'

export function initializeErrorFeedback(rules: ErrorFeedbackRules = {}) {
  return installErrorFeedback((feedback) => {
    if (feedback.kind === 'dialog') {
      void showDialog({
        title: feedback.title ?? '操作未完成',
        message: feedback.message,
        confirmButtonText: '知道了',
        allowHtml: false,
      }).catch(() => {})
    } else showFailToast({ message: feedback.message, duration: 4000 })
  }, rules)
}
