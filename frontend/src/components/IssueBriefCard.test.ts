// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';

import IssueBriefCard from '@/components/IssueBriefCard.vue';

const BRIEF = {
  colloquial: '你要做一个记录澄清项的小模块，确认后推送。',
  feasibility: '声明式能力内可直接落地，风险低。',
  agentPrompt: '请制作 FlexForge Level 1 插件：实体 clarify_item…',
};

const briefJson = JSON.stringify(BRIEF);

function mountCard(json: string | null) {
  return mount(IssueBriefCard, { props: { briefJson: json } });
}

describe('IssueBriefCard 三段简报分区（P23 FR-ISSUE-03B）', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('渲染三段分区与受众标签，agent 提示词可复制', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    const wrapper = mountCard(briefJson);
    expect(wrapper.find('[data-testid="issue-brief"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('口语化需求确认');
    expect(wrapper.text()).toContain(BRIEF.colloquial);
    expect(wrapper.text()).toContain('可行性说明');
    expect(wrapper.text()).toContain(BRIEF.feasibility);
    expect(wrapper.text()).toContain(BRIEF.agentPrompt);
    await wrapper.find('[data-testid="copy-agent-prompt"]').trigger('click');
    await flushPromises();
    expect(writeText).toHaveBeenCalledWith(BRIEF.agentPrompt);
    expect(wrapper.text()).toContain('已复制');
  });

  it('非法 JSON 或缺段时整卡隐藏（不渲染半截简报）', () => {
    expect(mountCard(null).find('[data-testid="issue-brief"]').exists()).toBe(false);
    expect(mountCard('not-json').find('[data-testid="issue-brief"]').exists()).toBe(false);
    const partial = JSON.stringify({ colloquial: 'c', feasibility: 'f' });
    expect(mountCard(partial).find('[data-testid="issue-brief"]').exists()).toBe(false);
  });

  it('剪贴板不可用不误报成功', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
      configurable: true,
    });
    const wrapper = mountCard(briefJson);
    await wrapper.find('[data-testid="copy-agent-prompt"]').trigger('click');
    await flushPromises();
    expect(wrapper.text()).not.toContain('已复制');
  });
});
