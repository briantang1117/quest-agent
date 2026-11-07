<template>
  <section class="page-heading"><div><p class="breadcrumb">管理工作台 / 题库管理</p><h1>让每一道题，都有可靠的来处。</h1><p class="lead">题目查询、编辑与审核将在这里汇合。当前不请求尚未实现的题库接口。</p></div></section>
  <a-alert type="info">管理业务与角色认证尚未接入。此页仅展示工程空壳，不提供管理权限，也不读取受保护的业务数据。</a-alert>
  <section class="panel question-panel"><div class="panel-heading"><h2>题库</h2><a-button disabled type="primary">新建题目（未接入）</a-button></div><div class="table-heading"><span>题目内容</span><span>题型</span><span>审核状态</span></div><div class="empty-state"><span class="empty-glyph" aria-hidden="true">题</span><h3>尚未连接题库</h3><p>这不是空题库。题库接口尚未接入，因此无法展示真实题目。</p><RouterLink to="/login" class="text-link">前往连接设置</RouterLink></div></section>
  <section class="admin-note"><h2>需要导入或整理题目？</h2><p>题库 Agent 将辅助完成导入、校验与审核。目前只保留入口，不创建会话或生成内容。</p><RouterLink to="/admin/agent" class="text-link">查看 Agent 接入状态</RouterLink></section>
</template>
