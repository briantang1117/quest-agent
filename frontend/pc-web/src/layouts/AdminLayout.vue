<template>
  <div class="admin-layout">
    <aside class="sidebar">
      <div class="sidebar-title">账号管理后台</div>
      <nav aria-label="管理导航">
        <RouterLink to="/admin/users" exact-active-class="selected">普通用户账号</RouterLink>
      </nav>
      <p class="sidebar-note">管理员仅管理账号。不提供学习、私有内容访问或模拟用户登录。</p>
    </aside>
    <main id="main" class="admin-main"><RouterView /></main>
  </div>
</template>
