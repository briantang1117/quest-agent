import type { CoachSession } from '@quest/shared'

const explainRetry = '请基于已保存的作答和判分，重新讲解这道题，说明思路与易错点。'
const legacyFailures = new Set([
  'AI讲解未完成，你的作答已保存，可以先查看原解析；不会自动重新生成。',
  '本次回答未完成，未自动重试。你可以重新发送问题。',
  'Token 余额不足，请联系管理员充值后重试。',
  'Token 余额不足，请联系管理员充值后重试。你的作答已保存，可以先查看原解析。',
  '此功能尚未开放，请联系管理员。',
  '此功能尚未开放，请联系管理员。你的作答已保存，可以先查看原解析。',
])

/** 仅允许重试当前末尾失败，旧回复不套用后续题目或对话上下文。 */
export function failedReplyRetry(session: CoachSession | null) {
  const message = session?.messages.at(-1)
  if (!message || message.role !== 'assistant') return null
  if (!message.retryText && !legacyFailures.has(message.text)) return null
  const previous = session!.messages
    .slice(0, -1)
    .reverse()
    .find((item) => item.role === 'user')
  if (!previous) return null
  const graded = message.text.includes('你的作答已保存')
  const text = message.retryText ?? (graded ? explainRetry : previous.text)
  if (!text.trim() || text.length > 2000) return null
  return {
    messageId: message.id,
    text,
    attachments: graded ? [] : (previous.attachments ?? []).map((item) => item.id),
  }
}
