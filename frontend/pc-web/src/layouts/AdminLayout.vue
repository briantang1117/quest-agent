<template>
  <div class="admin-layout">
    <aside class="sidebar"><div class="sidebar-title">管理工作台</div><nav aria-label="管理导航"><RouterLink to="/admin" exact-active-class="selected">题库管理</RouterLink><RouterLink to="/admin/agent" exact-active-class="selected">题库 Agent <span class="nav-label">未接入</span></RouterLink></nav><p class="sidebar-note">当前为公开的空壳预览，不包含管理数据。真实权限与角色校验待认证接口接入。</p></aside>
    <main id="main" class="admin-main"><RouterView /></main>
  </div>
</template>
