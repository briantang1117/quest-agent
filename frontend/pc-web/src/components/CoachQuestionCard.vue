<script setup lang="ts">
import { computed } from 'vue'
import type { CoachQuestion, CoachResult } from '@quest/shared'
import SafeMarkdown from './SafeMarkdown.vue'
// 交互态和只读态共用 .option-card 外观，只换颜色语义：
// 交互态选中走主题色；只读态选对绿色、选错红色，正确答案绿色描边。
const props = defineProps<{
  question: CoachQuestion
  result?: CoachResult | null
  interactive?: boolean
  disabled?: boolean
  selected?: string[]
}>()
const emit = defineEmits<{
  'update:selected': [string[]]
  submit: []
}>()
const typeLabel = computed(() => (props.question.type === 'single_choice' ? '单选题' : '多选题'))
const review = computed(() => !!props.result)
const locked = computed(() => !props.interactive || props.disabled)
const single = computed({
  get: () => props.selected?.[0] ?? '',
  set: (value: string) => emit('update:selected', value ? [value] : []),
})
const multiple = computed({
  get: () => props.selected ?? [],
  set: (value: string[]) => emit('update:selected', value),
})
// 只读态每项语义：我选的 / 正确答案 / 选错了。
function optionState(key: string) {
  const result = props.result
  if (!result) return {}
  const chosen = result.selectedKeys.includes(key)
  const answer = result.answerKeys.includes(key)
  return { 'is-selected': chosen, 'is-correct': answer, 'is-wrong': chosen && !answer }
}
</script>
<template>
  <section class="coach-question" :class="{ 'is-review': review }" aria-label="题目">
    <div class="question-type">
      {{ typeLabel
      }}<span v-if="review" class="review-tag"> {{ result!.correct ? '已答对' : '已作答' }} </span
      ><span v-else-if="!interactive" class="review-tag">已跳过</span>
    </div>
    <SafeMarkdown :source="question.stemMd" />

    <!-- 交互态：真 radio/checkbox，外观由 .option-card 统一控制。 -->
    <template v-if="!review && interactive">
      <a-radio-group
        v-if="question.type === 'single_choice'"
        v-model="single"
        class="option-group"
        direction="vertical"
        :disabled="locked"
        ><a-radio v-for="option in question.options" :key="option.key" :value="option.key">
          <!-- 用 #radio 插槽整块自绘，替换掉 Arco 默认的前置圆圈。 -->
          <template #radio="{ checked }">
            <span class="option-card" :class="{ 'is-checked': checked }">
              <span class="option-key">{{ option.key }}.</span>
              <span class="option-text">{{ option.textMd }}</span>
            </span>
          </template>
        </a-radio></a-radio-group
      >
      <a-checkbox-group
        v-else
        v-model="multiple"
        class="option-group"
        direction="vertical"
        :disabled="locked"
        ><a-checkbox v-for="option in question.options" :key="option.key" :value="option.key">
          <!-- checkbox 的 #checkbox 只换图标，正文仍由默认插槽渲染。 -->
          <template #checkbox="{ checked }">
            <span class="option-card" :class="{ 'is-checked': checked }">
              <span class="option-key">{{ option.key }}.</span>
              <span class="option-text">{{ option.textMd }}</span>
            </span>
          </template>
        </a-checkbox></a-checkbox-group
      >
      <a-button
        data-test="submit-answer"
        type="primary"
        :loading="disabled"
        :disabled="!selected?.length"
        @click="emit('submit')"
        >提交答案</a-button
      >
    </template>

    <!-- 只读态：同样的卡片外观，去掉表单控件。 -->
    <ul v-else class="option-group">
      <li
        v-for="option in question.options"
        :key="option.key"
        :data-option="option.key"
        class="option-card"
        :class="optionState(option.key)"
      >
        <span class="option-key">{{ option.key }}.</span>
        <span class="option-text">{{ option.textMd }}</span>
        <span v-if="result?.selectedKeys.includes(option.key)" class="option-flag">我的选择</span>
        <span
          v-if="result && result.answerKeys.includes(option.key) && !result.correct"
          class="option-flag answer"
          >正确答案</span
        >
      </li>
    </ul>

    <div v-if="review" class="coach-result">
      <strong>{{ result!.correct ? '回答正确' : '这次回答不正确' }}</strong>
      <p>
        我的答案：{{ result!.selectedKeys.join('、') || '未作答' }}
        <span class="answer-gap" aria-hidden="true"></span>正确答案：{{
          result!.answerKeys.join('、')
        }}
      </p>
      <SafeMarkdown :source="result!.explanationMd" />
    </div>
  </section>
</template>
<style scoped>
.coach-question {
  border: 1px solid var(--color-border-2);
  border-top: 3px solid #087d84;
  padding: 20px;
  margin-top: 12px;
  background: white;
}
/* 历史题目弱化边框，但正文对比度不变，不用 disabled 灰。 */
.coach-question.is-review {
  border-top-color: var(--color-border-2);
  background: var(--color-fill-1);
}
.question-type {
  font-size: 12px;
  color: var(--color-text-3);
  margin-bottom: 12px;
}
.review-tag {
  margin-left: 8px;
}

/* 选项卡片：交互态与只读态共用的唯一外观。 */
.option-group {
  list-style: none;
  padding: 0;
  margin: 16px 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}
.option-card {
  display: flex;
  align-items: baseline;
  gap: 8px;
  width: 100%;
  box-sizing: border-box;
  padding: 10px 14px;
  border: 1px solid var(--color-border-2);
  border-radius: 2px;
  background: white;
  line-height: 1.7;
  transition:
    border-color 0.15s,
    background-color 0.15s;
}
.option-key {
  font-weight: 600;
  flex: none;
}
.option-text {
  flex: 1;
  white-space: normal;
}

/* 交互态：整块卡片可点。自绘后不再有 Arco 的图标与 label 包裹层。 */
.option-group :deep(.arco-radio),
.option-group :deep(.arco-checkbox) {
  width: 100%;
  margin: 0;
  padding: 0;
  display: flex;
  /* 单选/多选都不再显示自带图标，间距也一并归零。 */
  gap: 0;
  align-items: stretch;
}
.option-group :deep(.arco-radio-icon-hover),
.option-group :deep(.arco-checkbox-icon-hover) {
  display: none;
}
.option-group :deep(.arco-radio-label),
.option-group :deep(.arco-checkbox-label) {
  width: 100%;
  padding-left: 0;
}
.option-group :deep(.arco-radio:hover) .option-card,
.option-group :deep(.arco-checkbox:hover) .option-card {
  border-color: rgb(var(--primary-5));
}
/* 选中态用卡片自己的 is-checked，避免依赖 Arco 外层类名漂移。 */
.option-card.is-checked {
  border-color: rgb(var(--primary-6));
  background: rgb(var(--primary-1));
}
.option-card.is-checked .option-key {
  color: rgb(var(--primary-6));
}

/* 只读态：选对绿色、选错红色，与卡片边框统一。 */
.option-card.is-correct {
  border-color: #00b42a;
  background: #e8ffea;
}
.option-card.is-wrong {
  border-color: #f53f3f;
  background: #ffece8;
}
.option-flag {
  flex: none;
  font-size: 12px;
  padding: 1px 6px;
  border-radius: 2px;
  background: rgb(var(--primary-1));
  color: rgb(var(--primary-6));
}
/* 标签颜色必须跟随所在卡片的状态色，不能出现绿底配青字这种混搭。 */
.option-card.is-correct .option-flag {
  background: #e8ffea;
  color: #00b42a;
}
.option-card.is-wrong .option-flag {
  background: #ffece8;
  color: #f53f3f;
}

.coach-result {
  margin-top: 20px;
  border-top: 1px solid var(--color-border-2);
  padding-top: 16px;
}
.coach-result p {
  font-size: 13px;
}
.answer-gap {
  display: inline-block;
  width: 2ch;
}
</style>
