import { onUnmounted, ref } from 'vue';

import { ApiError, apiErrorMessage } from '@/api/client';
import { listUsers, type SystemUser } from '@/api/system';

/** Issue #51: FR-AUTH-03/05/06, latest-request-wins paging through the existing API. */
export function useUserPage() {
  const users = ref<SystemUser[]>([]);
  const state = ref<'loading' | 'ready' | 'error' | 'denied' | 'empty'>('loading');
  const error = ref<string | null>(null);
  const pageNumber = ref(1);
  const total = ref(0);
  const pageSize = 50;
  let sequence = 0;

  async function load(): Promise<void> {
    const request = ++sequence;
    state.value = 'loading';
    error.value = null;
    users.value = [];
    try {
      const result = await listUsers(pageNumber.value, pageSize);
      if (request !== sequence) return;
      users.value = result.items;
      total.value = result.total;
      pageNumber.value = result.pageNumber;
      state.value = result.items.length === 0 ? 'empty' : 'ready';
    } catch (e) {
      if (request !== sequence) return;
      state.value = e instanceof ApiError && e.status === 403 ? 'denied' : 'error';
      if (state.value === 'error') error.value = apiErrorMessage(e, null);
    }
  }

  onUnmounted(() => sequence++);
  return { users, state, error, pageNumber, pageSize, total, load };
}
