import type { CoachMessage, Material } from '@quest/shared'

/** 使用持久化消息关联；轮询完成与标题变化均不改变资料在对话中的位置。 */
export function groupCoachMaterials(messages: CoachMessage[], materials: Material[]) {
  const pending = new Map(materials.map((item) => [item.id, item]))
  const groups = new Map<string, Material[]>()
  const attach = (messageId: string, material: Material) => {
    groups.set(messageId, [...(groups.get(messageId) ?? []), material])
    pending.delete(material.id)
  }
  for (const message of messages) {
    const material = message.materialId ? pending.get(message.materialId) : undefined
    if (material) attach(message.id, material)
  }
  const sorted = [...pending.values()].sort(
    (a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id),
  )
  for (const material of sorted) {
    const index = messages.findIndex((message) => message.id === material.sourceMessageId)
    if (index >= 0) {
      // 老任务没有 materialId：沿快照末尾寻找紧接着的创建通知。
      const after = messages.slice(index + 1)
      const notice = after.find(
        (message) =>
          !message.materialId &&
          !groups.has(message.id) &&
          (message.materialNotice ||
            message.text === '已创建学习资料整理任务，完成后可在文件卡片或学习资料中下载。'),
      )
      const nextReply = after.find((message) => message.role === 'assistant')
      attach(notice && notice === nextReply ? notice.id : messages[index]!.id, material)
    } else if (!material.sourceMessageId) {
      // 兼容尚未升级API的旧快照：按创建顺序匹配尚未占用的通知。
      const notice = messages.find(
        (message) =>
          message.role === 'assistant' &&
          !groups.has(message.id) &&
          (message.materialNotice ||
            message.text === '已创建学习资料整理任务，完成后可在文件卡片或学习资料中下载。'),
      )
      if (notice) attach(notice.id, material)
    }
  }
  return { messages: groups, earlier: sorted.filter((item) => pending.has(item.id)) }
}
