// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it } from 'vitest';

import AssistantComposer from '@/components/AssistantComposer.vue';

const stubs = { teleport: true };

function mountComposer() {
  return mount(AssistantComposer, { props: { asking: false, resetKey: 0 }, global: { stubs } });
}

/** happy-dom 无真实文件选择 UI：直接给 input.files 赋值后派发 change。 */
async function pickFiles(wrapper: Awaited<ReturnType<typeof mount>>, files: File[]) {
  const input = wrapper.find('[data-testid="assistant-file-input"]').element;
  Object.defineProperty(input, 'files', { value: files, configurable: true });
  input.dispatchEvent(new Event('change'));
  await flushPromises();
}

async function inputValue(wrapper: Awaited<ReturnType<typeof mount>>): Promise<string> {
  return (wrapper.find('[data-testid="assistant-input"]').element as HTMLTextAreaElement).value;
}

describe('AssistantComposer 附件选择与客户端预检（FR-KB-05）', () => {
  beforeEach(() => {
    globalThis.sessionStorage?.clear();
  });

  it('选择附件呈 chips 且可移除', async () => {
    const wrapper = mountComposer();
    await pickFiles(wrapper, [
      new File(['a,b\n1,2'], '清单.csv', { type: 'text/csv' }),
      new File([new Uint8Array([1, 2, 3])], '图.png', { type: 'image/png' }),
    ]);
    const chips = wrapper.findAll('[data-testid="assistant-pending"] li');
    expect(chips).toHaveLength(2);
    expect(chips[0].text()).toContain('清单.csv');
    await chips[0].find('button').trigger('click');
    expect(wrapper.findAll('[data-testid="assistant-pending"] li')).toHaveLength(1);
  });

  it('坏扩展拒收并提示；数量超限截断到 3', async () => {
    const wrapper = mountComposer();
    await pickFiles(wrapper, [new File(['x'], '工具.exe', { type: 'application/x-msdownload' })]);
    expect(wrapper.find('[data-testid="assistant-attach-error"]').text()).toContain(
      '不支持的附件类型',
    );
    expect(wrapper.findAll('[data-testid="assistant-pending"] li')).toHaveLength(0);

    await pickFiles(wrapper, [
      new File(['1'], 'a.txt'),
      new File(['2'], 'b.txt'),
      new File(['3'], 'c.txt'),
      new File(['4'], 'd.txt'),
    ]);
    expect(wrapper.findAll('[data-testid="assistant-pending"] li')).toHaveLength(3);
    expect(wrapper.find('[data-testid="assistant-attach-error"]').text()).toContain('3 个附件');
  });

  it('空文本不可发送（按钮禁用）', () => {
    const wrapper = mountComposer();
    expect(wrapper.find('[data-testid="assistant-send"]').attributes('disabled')).toBeDefined();
  });
});

describe('AssistantComposer 发送与清空（失败保留原稿）', () => {
  beforeEach(() => {
    globalThis.sessionStorage?.clear();
  });

  it('随 send 上抛文本与附件；resetKey 递增才清空', async () => {
    const wrapper = mountComposer();
    const png = new File([new Uint8Array([1, 2, 3])], '图.png', { type: 'image/png' });
    await pickFiles(wrapper, [png]);
    await wrapper.find('[data-testid="assistant-input"]').setValue('看看附件');
    await wrapper.find('[data-testid="assistant-send"]').trigger('click');
    expect(wrapper.emitted('send')?.[0]).toEqual(['看看附件', [png]]);
    // 发送未确认（resetKey 未变）：原稿保留便于失败重试
    expect(wrapper.findAll('[data-testid="assistant-pending"] li')).toHaveLength(1);
    expect(await inputValue(wrapper)).toBe('看看附件');
    await wrapper.setProps({ resetKey: 1 });
    expect(wrapper.findAll('[data-testid="assistant-pending"] li')).toHaveLength(0);
    expect(await inputValue(wrapper)).toBe('');
  });
});
