<template>
  <section class="page-heading">
    <div>
      <p class="breadcrumb">学习 / 刷题空间</p>
      <h1>从一道题，开始理清思路。</h1>
      <p class="lead">这里将承载日常练习、错题复习与真题模考。现在先把学习空间准备好。</p>
    </div>
    <a-tag color="cyan">基础框架已就绪</a-tag>
  </section>
  <section class="practice-stage">
    <div class="stage-copy">
      <span class="subtle-label">你的练习区域</span>
      <h2>题库接入后，<br />在这里开始第一次练习。</h2>
      <p>你已进入自己的学习工作区。私有题库与练习服务尚未接入，当前不会读取题目或生成学习记录。</p>
      <a-button type="primary" disabled>开始练习（未接入）</a-button
      ><RouterLink class="text-link" to="/practice/agent">查看个人 Agent 接入状态</RouterLink>
    </div>
    <div class="study-sheet" aria-hidden="true">
      <div class="sheet-tab">学习空间</div>
      <div class="sheet-line long"></div>
      <div class="sheet-line"></div>
      <div class="sheet-option"><i></i><span></span></div>
      <div class="sheet-option"><i></i><span></span></div>
      <div class="sheet-option"><i></i><span></span></div>
      <div class="sheet-caption">等待真实题目</div>
    </div>
  </section>
  <section class="feature-list" aria-label="功能接入状态">
    <article>
      <span class="feature-symbol">练</span>
      <div>
        <h3>日常练习</h3>
        <p>按知识点选题、作答与解析，等待练习服务接入。</p>
      </div>
      <a-tag>未接入</a-tag>
    </article>
    <article>
      <span class="feature-symbol">复</span>
      <div>
        <h3>错题复习</h3>
        <p>基于真实作答记录整理错题，不预填学习统计。</p>
      </div>
      <a-tag>未接入</a-tag>
    </article>
    <article>
      <span class="feature-symbol">考</span>
      <div>
        <h3>真题模考</h3>
        <p>试卷、计时与成绩报告将在后续业务阶段实现。</p>
      </div>
      <a-tag>未接入</a-tag>
    </article>
  </section>
</template>
