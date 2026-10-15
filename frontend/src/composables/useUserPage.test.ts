// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import { defineComponent } from 'vue';

import { listUsers } from '@/api/system';
import { useUserPage } from '@/composables/useUserPage';

vi.mock('@/api/system', () => ({ listUsers: vi.fn() }));

describe('Issue #51 latest-request-wins paging', () => {
  it.each(['success', 'error'])('ignores late %s from an obsolete request', async (outcome) => {
    const listMock = vi.mocked(listUsers);
    listMock.mockReset();
    let resolve!: (value: Awaited<ReturnType<typeof listUsers>>) => void;
    let reject!: (reason: Error) => void;
    listMock.mockReturnValueOnce(
      new Promise((yes, no) => {
        resolve = yes;
        reject = no;
      }),
    );
    listMock.mockResolvedValueOnce({ items: [], total: 51, pageNumber: 2, pageSize: 50 });
    let page!: ReturnType<typeof useUserPage>;
    const wrapper = mount(
      defineComponent({
        setup() {
          page = useUserPage();
          return () => null;
        },
      }),
    );
    const oldRequest = page.load();
    page.pageNumber.value = 2;
    await page.load();
    if (outcome === 'success') {
      resolve({ items: [], total: 1, pageNumber: 1, pageSize: 50 });
    } else {
      reject(new Error('obsolete request'));
    }
    await oldRequest;
    expect(page.pageNumber.value).toBe(2);
    expect(page.total.value).toBe(51);
    expect(page.state.value).toBe('empty');
    expect(page.error.value).toBeNull();
    wrapper.unmount();
  });
});
