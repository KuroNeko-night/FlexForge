<script setup lang="ts">
import { ref } from 'vue';

import { apiErrorMessage } from '@/api/client';
import { addComment, fetchComments, type IssueComment } from '@/api/issues';
import BaseButton from '@/components/ui/BaseButton.vue';

/**
 * 用户端讨论区（P23 FR-ISSUE-07）：已发布/梳理中需求的评论区——查看与
 * 参与讨论。评论提交后自行刷新（reloaded 上抛，父层同步列表态）。
 */
const props = defineProps<{ issueId: string; comments: IssueComment[] }>();
const emit = defineEmits<{ reloaded: [comments: IssueComment[]] }>();

const draft = ref('');
const error = ref<string | null>(null);
const sending = ref(false);

async function submit(): Promise<void> {
  const text = draft.value.trim();
  if (text === '' || sending.value) {
    return;
  }
  const issueId = props.issueId;
  sending.value = true;
  error.value = null;
  try {
    await addComment(issueId, text);
    draft.value = '';
    const next = await fetchComments(issueId);
    if (props.issueId === issueId) {
      emit('reloaded', next);
    }
  } catch (e) {
    error.value = apiErrorMessage(e, '评论失败，请稍后重试');
  } finally {
    sending.value = false;
  }
}
</script>

<template>
  <section class="discussion" data-testid="issue-discussion">
    <h4>讨论</h4>
    <ul v-if="comments.length > 0">
      <li v-for="comment in comments" :key="comment.id">
        <span class="comment-author">{{ comment.author }}</span>
        <p>{{ comment.body }}</p>
      </li>
    </ul>
    <p v-else class="empty-hint">还没有讨论</p>
    <form class="comment-form" @submit.prevent="submit">
      <input
        v-model="draft"
        data-testid="comment-input"
        placeholder="补充说明或参与讨论…"
        maxlength="2000"
      />
      <BaseButton type="submit" :disabled="sending || draft.trim() === ''">发送</BaseButton>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.discussion {
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
  padding-top: var(--ff-space-3);
  border-top: 1px solid var(--ff-border-soft);
}
.discussion h4 {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.discussion ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ff-space-2);
}
.discussion li {
  padding: var(--ff-space-2);
  border-radius: var(--ff-radius-md);
  background: var(--ff-surface-muted);
}
.comment-author {
  font-size: var(--ff-text-xs, 0.75rem);
  color: var(--ff-text-muted);
}
.discussion li p {
  margin: var(--ff-space-1) 0 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.empty-hint {
  margin: 0;
  font-size: var(--ff-text-sm);
  color: var(--ff-text-muted);
}
.comment-form {
  display: flex;
  gap: var(--ff-space-2);
}
.comment-form input {
  flex: 1;
  padding: var(--ff-space-2);
  border-radius: var(--ff-radius-md);
}
</style>
