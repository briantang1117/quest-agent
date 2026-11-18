import { expect, it } from 'vitest'
import type { CoachMessage, Material } from '@quest/shared'
import { groupCoachMaterials } from '../composables/coach-materials'

const material: Material = {
  id: 'doc',
  sessionId: 'session',
  sourceTitle: '讲解',
  sourceDeleted: false,
  subject: 'vocational_aptitude',
  title: '资料',
  status: 'ready',
  format: 'markdown',
  createdAt: '2026-11-18T10:00:00Z',
  updatedAt: '2026-11-18T11:00:00Z',
  error: null,
  sourceMessageId: 'origin',
}
const messages: CoachMessage[] = [
  { id: 'origin', role: 'assistant', text: '原讲解' },
  { id: 'request', role: 'user', text: '生成md' },
  {
    id: 'notice',
    role: 'assistant',
    text: '已创建学习资料整理任务，完成后可在文件卡片或学习资料中下载。',
  },
  { id: 'later', role: 'user', text: '新问题' },
]
it('旧资料用不可变快照定位创建通知', () => {
  const result = groupCoachMaterials(messages, [material])
  expect(result.messages.get('notice')).toEqual([material])
  expect(result.earlier).toEqual([])
})
it('顶部按钮创建的资料挂在快照末尾，不匹配后续另一任务的通知', () => {
  const later: CoachMessage[] = [
    messages[0]!,
    { id: 'answer', role: 'assistant', text: '后续讲解' },
    ...messages.slice(1),
  ]
  expect(groupCoachMaterials(later, [material]).messages.get('origin')).toEqual([material])
})
it('明确的任务关联优先于快照，避免同一资料重复展示', () => {
  const linked = messages.map((message) =>
    message.id === 'notice' ? { ...message, materialId: material.id } : message,
  )
  const result = groupCoachMaterials(linked, [material])
  expect(result.messages.size).toBe(1)
  expect(result.messages.get('notice')).toEqual([material])
})
it('消息被裁剪的旧资料放在更早资料区域，不能挤到新消息后', () => {
  expect(groupCoachMaterials(messages.slice(-1), [material]).earlier).toEqual([material])
})
