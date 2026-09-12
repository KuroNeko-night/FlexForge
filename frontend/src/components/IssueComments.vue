<script setup lang="ts">
import { ref } from 'vue';

import { apiErrorMessage } from '@/api/client';
import { addComment, fetchComments, type IssueComment } from '@/api/issues';
import BaseButton from '@/components/ui/BaseButton.vue';
import ComponentCard from '@/components/ui/ComponentCard.vue';
import { t } from '@/registry/localeRegistry';

/**
 * Issue 评论区（P16 从 IssueDetail 拆出）：列表 + 发表；评论是登录用户能力，
 * 失败信息就地呈现。父级负责随 Issue 切换重载（传入 comments；缺省空表——
 * 测试/异步挂载路径的未传防御，P29 审查回归中发现）。
 */
const props = withDefaults(defineProps<{ issueId: string; comments?: IssueComment[] }>(), {
  comments: () => [],
});
const emit = defineEmits<{ reloaded: [comments: IssueComment[]] }>();

const body = ref('');
const commenting = ref(false);
const error = ref<string | null>(null);

async function submit(): Promise<void> {
  const text = body.value.trim();
  if (commenting.value || text === '') {
    return;
  }
  commenting.value = true;
  error.value = null;
  try {
    await addComment(props.issueId, text);
    body.value = '';
    emit('reloaded', await fetchComments(props.issueId));
  } catch (e) {
    error.value = apiErrorMessage(e, t('issues.commentFailed', '评论失败，请稍后重试'));
  } finally {
    commenting.value = false;
  }
}
</script>

<template>
  <ComponentCard
    :title="t('issues.comments', '评论')"
    :subtitle="`${comments.length} ${t('issues.countUnit', '条')}`"
  >
    <ul class="comment-list">
      <li v-for="comment in comments" :key="comment.id">
        <span class="comment-author">{{ comment.author }}</span>
        <span class="comment-body">{{ comment.body }}</span>
      </li>
      <li v-if="comments.length === 0" class="comment-empty">
        {{ t('issues.noComments', '尚无评论') }}
      </li>
    </ul>
    <div class="comment-input">
      <textarea
        v-model="body"
        rows="2"
        :placeholder="t('issues.commentPlaceholder', '发表评论…')"
        :disabled="commenting"
      />
      <BaseButton :disabled="commenting || body.trim() === ''" @click="submit">
        {{ commenting ? t('issues.commenting', '发表中…') : t('issues.commentSubmit', '发表') }}
      </BaseButton>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </ComponentCard>
</template>

<style scoped>
.comment-list {
  list-style: none;
  padding: 0;
  margin: 0 0 var(--ff-space-2);
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.comment-author {
  font-weight: 600;
  margin-right: var(--ff-space-2);
}
.comment-empty {
  color: var(--ff-text-muted);
  font-size: var(--ff-text-sm);
}
.comment-input {
  display: flex;
  gap: var(--ff-space-2);
  align-items: flex-end;
}
.comment-input textarea {
  flex: 1;
  padding: var(--ff-space-2);
  resize: vertical;
}
</style>
