<template>
  <main class="mobile-main"><div class="preview-note">工程预览 · Agent 业务未接入</div><section class="mobile-intro"><h1>把不懂的，<br>慢慢弄懂。</h1><p>这里将是你的学习对话空间。刷题、答疑与学情分析，都从真实会话开始。</p></section>
    <section class="session-empty"><div class="conversation-mark" aria-hidden="true"><span>问</span><span>思</span></div><h2>还不能开始对话</h2><p>Agent 会话服务尚未实现。没有加载历史会话，也不会生成模拟回答。</p><van-button type="primary" block round disabled>新建会话（未接入）</van-button><RouterLink class="text-link" to="/login">先检查服务连接</RouterLink></section>
    <section class="mobile-boundary"><h2>这个空间只做学习对话</h2><p>不包含题库管理或后台功能。真实抽题、判分与解析，待 Agent 工具接入后开放。</p></section>
  </main>
</template>
