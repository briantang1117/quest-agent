<script setup lang="ts">
import { onActivated, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  createCoachApi,
  createSyllabusStudyApi,
  notifyError,
  subjectLabel,
  useAuthStore,
  type StudySubject,
  type StudySyllabus,
  type SyllabusCitation,
} from '@quest/shared'
import SafeMarkdown from '../components/SafeMarkdown.vue'
const auth = useAuthStore(),
  coach = createCoachApi(auth),
  api = createSyllabusStudyApi(auth),
  router = useRouter()
const subject = ref<StudySubject | null>(null),
  year = ref<number | null>(null),
  syllabus = ref<StudySyllabus | null>(null)
const loading = ref(false),
  failed = ref(false),
  busy = ref(false)
let revision = 0,
  alive = true
async function load() {
  if (loading.value) return
  const version = ++revision
  loading.value = true
  failed.value = false
  syllabus.value = null
  try {
    const preference = await coach.preference()
    if (!alive || version !== revision) return
    subject.value = preference.subject
    year.value = preference.examYear
    if (subject.value && year.value) {
      const value = await api.get(subject.value, year.value)
      if (alive && version === revision) syllabus.value = value
    }
  } catch (e) {
    if (alive && version === revision) {
      failed.value = true
      notifyError(e)
    }
  } finally {
    if (alive && version === revision) loading.value = false
  }
}
async function explain(section: SyllabusCitation) {
  if (busy.value || !syllabus.value?.versionId) return
  const version = revision
  busy.value = true
  try {
    const value = await coach.create(crypto.randomUUID(), 'chat')
    if (!alive || version !== revision) return
    // 创建时偏好或已发布版本可能改变，不把旧页面资料伪装成新会话的依据。
    if (value.syllabusVersionId !== syllabus.value.versionId) {
      await load()
      return
    }
    await router.push({
      path: '/practice/agent',
      query: {
        session: value.id,
        prompt: `请结合${year.value}年考纲，讲讲“${section.sectionTitle}”主要考什么，以及我该怎么准备。`,
      },
    })
  } catch (e) {
    if (alive && version === revision) notifyError(e)
  } finally {
    if (alive && version === revision) busy.value = false
  }
}
onMounted(load)
onActivated(load)
onBeforeUnmount(() => {
  alive = false
  revision++
})
</script>
<template>
  <section class="syllabus-guide">
    <header class="guide-heading">
      <div>
        <RouterLink to="/practice">学习空间</RouterLink>
        <h1><span class="guide-symbol">纲</span>考纲导览</h1>
        <p>
          {{ subjectLabel(subject) }}<template v-if="year"> · {{ year }} 年</template>
        </p>
      </div>
      <RouterLink class="text-link" to="/practice/profile">调整科目与年份</RouterLink>
    </header>
    <a-spin :loading="loading" style="width: 100%">
      <a-empty v-if="failed" description="考纲加载失败"
        ><a-button @click="load">重试</a-button></a-empty
      >
      <a-empty
        v-else-if="!loading && (!subject || !year)"
        description="请先在个人中心选择科目和目标考试年份"
        ><RouterLink to="/practice/profile">前往设置</RouterLink></a-empty
      >
      <a-empty
        v-else-if="!loading && !syllabus?.versionId"
        description="该科目与年份暂无已发布考纲，不会自动使用其他年份"
      />
      <template v-else-if="syllabus?.versionId">
        <div class="guide-version">
          <h2>{{ syllabus.title }}</h2>
          <a-tag color="cyan">{{ syllabus.versionLabel }}</a-tag>
          <p>以下为当前启用的考纲片段，非整份文件全文。</p>
        </div>
        <a-empty v-if="!syllabus.sections.length" description="该版本暂无可展示的适用章节" />
        <article
          v-for="(section, index) in syllabus.sections"
          :key="`${section.versionId}-${index}`"
          class="guide-section"
        >
          <header>
            <h2>{{ section.sectionTitle }}</h2>
            <span>PDF 第 {{ section.pageStart }}—{{ section.pageEnd }} 页</span>
          </header>
          <SafeMarkdown :source="section.text" />
          <a-button data-test="explain-syllabus-section" :loading="busy" @click="explain(section)"
            >让 Agent 讲讲</a-button
          >
        </article>
      </template>
    </a-spin>
  </section>
</template>
<style scoped>
.guide-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 24px;
}
.guide-heading h1 {
  font-size: 26px;
  margin: 12px 0;
}
.guide-symbol {
  font-family: var(--font-serif);
  color: rgb(var(--primary-6));
  margin-right: 12px;
}
.guide-heading p,
.guide-version p {
  color: var(--color-text-3);
}
.guide-version,
.guide-section {
  padding: 24px;
  margin-bottom: 20px;
  background: white;
  border: 1px solid var(--color-border-2);
  border-radius: 2px;
}
.guide-version h2,
.guide-section h2 {
  font-size: 18px;
  margin: 0 0 12px;
}
.guide-section header span {
  color: var(--color-text-3);
  font-size: 12px;
}
.guide-section .safe-markdown {
  margin: 16px 0;
}
@media (max-width: 800px) {
  .guide-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
