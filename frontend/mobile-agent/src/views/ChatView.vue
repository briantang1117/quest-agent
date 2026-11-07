<template>
  <main class="mobile-main chat-page">
    <RouterLink class="back-link" to="/sessions">返回学习会话</RouterLink>
    <div class="chat-title">
      <h1>Agent 对话</h1>
      <van-tag plain>未接入</van-tag>
    </div>
    <div class="chat-empty">
      <span class="brand-mark large" aria-hidden="true">序</span>
      <h2>对话能力正在准备中</h2>
      <p>当前无法读取或创建真实会话。此页不生成回答，也不请求尚未实现的 Agent API。</p>
    </div>
    <div class="mobile-composer">
      <label for="chat-input">消息输入</label
      ><van-field
        id="chat-input"
        type="textarea"
        rows="2"
        disabled
        placeholder="Agent 服务接入后可发送消息"
      /><van-button type="primary" block disabled>发送（未接入）</van-button>
      <p>题目卡片、文件上传与工具执行待接入</p>
    </div>
  </main>
</template>
