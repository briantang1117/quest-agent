<template>
  <section class="page-heading">
    <div>
      <p class="breadcrumb">学习工作区 / 个人 Agent</p>
      <h1>个人 Agent</h1>
      <p class="lead">未来仅处理本人私有题库与学习问题。目前尚未接入对话、检索或工具执行。</p>
    </div>
    <a-tag>业务未实现</a-tag>
  </section>
  <section class="agent-workspace">
    <aside class="session-sidebar">
      <h2>我的会话</h2>
      <a-button long disabled>新建会话（未接入）</a-button>
      <p>没有加载会话列表。</p>
    </aside>
    <div class="agent-main">
      <div class="empty-state">
        <span class="agent-emblem" aria-hidden="true">序</span>
        <h2>先连接能力，再开始对话。</h2>
        <p>Agent 业务尚未实现。这里不会生成演示回答，也不会自动调用未上线的会话接口。</p>
        <RouterLink class="text-link" to="/practice">返回学习工作区</RouterLink>
      </div>
      <div class="composer">
        <label for="agent-draft">消息输入</label
        ><a-textarea
          id="agent-draft"
          disabled
          placeholder="Agent 服务接入后可输入内容，当前无法发送"
          :auto-size="{ minRows: 2, maxRows: 4 }"
        />
        <div>
          <span>文件上传、题目卡片与工具执行待实现</span
          ><a-button type="primary" disabled>发送</a-button>
        </div>
      </div>
    </div>
  </section>
</template>
