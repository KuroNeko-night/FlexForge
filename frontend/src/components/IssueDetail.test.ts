// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { IssueRecord, IssueStatusName, SpecRevision } from '@/api/issues';
import { saveSession } from '@/auth/token';
import IssueDetail from '@/components/IssueDetail.vue';

vi.mock('@/api/issues', async () => {
  const actual = await vi.importActual<typeof import('@/api/issues')>('@/api/issues');
  return {
    ...actual,
    fetchComments: vi.fn(),
    addComment: vi.fn(),
    fetchSpec: vi.fn(),
    clarifyIssue: vi.fn(),
    transitionIssue: vi.fn(),
    saveSpec: vi.fn(),
    fetchPreview: vi.fn(),
    generatePlugin: vi.fn(),
  };
});

import {
  addComment,
  clarifyIssue,
  fetchComments,
  fetchPreview,
  fetchSpec,
  generatePlugin,
  saveSpec,
  transitionIssue,
} from '@/api/issues';

const commentsMock = vi.mocked(fetchComments);
const specMock = vi.mocked(fetchSpec);
const clarifyMock = vi.mocked(clarifyIssue);
const transitionMock = vi.mocked(transitionIssue);
const saveSpecMock = vi.mocked(saveSpec);
const previewMock = vi.mocked(fetchPreview);
const generateMock = vi.mocked(generatePlugin);
const addCommentMock = vi.mocked(addComment);

const baseIssue: IssueRecord = {
  id: 'i1',
  title: '物料需求管理',
  description: '需要物料档案与库存记录',
  status: 'SUBMITTED',
  createdBy: 'alice',
  assignedTo: null,
  labels: [],
  createdAt: '2026-08-30T02:00:00Z',
  updatedAt: '2026-08-30T02:00:00Z',
};

const spec: SpecRevision = {
  id: 's1',
  issueId: 'i1',
  schemaVersion: 1,
  revision: 1,
  specJson: '{"schemaVersion":1}',
  valid: true,
  validationErrors: null,
  createdBy: 'ai',
  createdAt: '2026-08-30T02:01:00Z',
};

function resetMocks(): void {
  commentsMock.mockReset();
  specMock.mockReset();
  clarifyMock.mockReset();
  transitionMock.mockReset();
  saveSpecMock.mockReset();
  previewMock.mockReset();
  generateMock.mockReset();
  addCommentMock.mockReset();
}

function mountDetail(
  status: IssueStatusName = 'SUBMITTED',
  roles: string[] = ['DEVELOPER'],
  createdBy = 'dev',
) {
  saveSession('t', { id: 1, username: 'dev', displayName: 'D', roles });
  return mount(IssueDetail, { props: { issue: { ...baseIssue, status, createdBy } } });
}

async function clickButton(wrapper: ReturnType<typeof mountDetail>, label: string) {
  const button = wrapper.findAll('button').find((b) => b.text().includes(label))!;
  await button.trigger('click');
  await flushPromises();
}

describe('IssueDetail AI 对话（clarify，FR-ISSUE-03）', () => {
  beforeEach(() => {
    resetMocks();
    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
  });

  it('开始澄清：首轮无回答→AI 追问渲染为对话', async () => {
    clarifyMock.mockResolvedValue({ specProduced: false, questions: ['实体叫什么？'], spec: null });
    const wrapper = mountDetail();
    await flushPromises();
    await clickButton(wrapper, '开始 AI 澄清');
    expect(clarifyMock).toHaveBeenCalledWith('i1', undefined);
    expect(wrapper.find('[data-testid="clarify-chat"]').text()).toContain('实体叫什么？');
  });

  it('提交回答后生成规格草稿：spec 更新并提示', async () => {
    clarifyMock.mockResolvedValueOnce({ specProduced: false, questions: ['实体叫什么？'], spec: null });
    const wrapper = mountDetail();
    await flushPromises();
    await clickButton(wrapper, '开始 AI 澄清');
    clarifyMock.mockResolvedValueOnce({ specProduced: true, questions: [], spec });
    await wrapper.find('textarea').setValue('物料档案 material_archive');
    await clickButton(wrapper, '提交回答');
    expect(clarifyMock).toHaveBeenLastCalledWith('i1', '物料档案 material_archive');
    expect(wrapper.find('[data-testid="spec-section"]').text()).toContain('修订 1');
  });

  it('模型不可用（503）显示可诊断错误，不破面板（FR-ISSUE-06 引导手工兜底）', async () => {
    clarifyMock.mockRejectedValue(new ApiError('model_unavailable', '模型不可用', 503, 'req-3'));
    const wrapper = mountDetail();
    await flushPromises();
    await wrapper.find('[data-testid="clarify-start"]').trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('模型不可用');
    expect(wrapper.text()).toContain('req-3');
    expect(wrapper.find('[data-testid="issue-detail"]').exists()).toBe(true);
  });

  it('非作者 USER 无 clarify 入口（后端同口径，前端只控显隐）', async () => {
    const wrapper = mountDetail('SUBMITTED', ['USER'], 'someone-else');
    await flushPromises();
    expect(wrapper.find('[data-testid="clarify-start"]').exists()).toBe(false);
  });
});

describe('IssueDetail 切换复位（PR #34 审查 P2）', () => {
  beforeEach(() => {
    resetMocks();
    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
  });

  it('切换 Issue 复位对话与草稿（组件复用防串台）', async () => {
    clarifyMock.mockResolvedValue({ specProduced: false, questions: ['A 的追问'], spec: null });
    const wrapper = mountDetail('SUBMITTED');
    await flushPromises();
    await clickButton(wrapper, '开始 AI 澄清');
    expect(wrapper.text()).toContain('A 的追问');
    await wrapper.find('.spec-editor').setValue('{"schemaVersion":1,"from":"a"}');

    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
    await wrapper.setProps({ issue: { ...baseIssue, id: 'i2', title: 'B' } });
    await flushPromises();
    expect(wrapper.text()).not.toContain('A 的追问');
    expect((wrapper.find('.spec-editor').element as HTMLTextAreaElement).value).toBe('');
  });
});

describe('IssueDetail 状态迁移（FR-ISSUE-02）', () => {
  beforeEach(() => {
    resetMocks();
    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
  });

  it('迁移：按状态机给可选目标，旁路迁移强制原因', async () => {
    const wrapper = mountDetail('SUBMITTED');
    await flushPromises();
    const select = wrapper.find('[data-testid="transition-target"]');
    const options = select.findAll('option').map((o) => o.element.value);
    expect(options).toEqual(expect.arrayContaining(['APPROVED', 'RETURNED']));

    transitionMock.mockResolvedValue({ ...baseIssue, status: 'APPROVED' });
    await select.setValue('RETURNED');
    await clickButton(wrapper, '执行迁移');
    expect(transitionMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('该迁移必须填写原因');
  });

  it('迁移成功后上抛 updated 同步父级列表', async () => {
    const wrapper = mountDetail('SUBMITTED');
    await flushPromises();
    transitionMock.mockResolvedValue({ ...baseIssue, status: 'APPROVED' });
    await wrapper.find('[data-testid="transition-target"]').setValue('APPROVED');
    await clickButton(wrapper, '执行迁移');
    expect(transitionMock).toHaveBeenCalledWith('i1', 'APPROVED', '');
    expect(wrapper.emitted('updated')?.[0]?.[0]).toMatchObject({ status: 'APPROVED' });
  });
});

describe('IssueDetail 规格与生成（FR-ISSUE-04/05/06）', () => {
  beforeEach(() => {
    resetMocks();
    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
  });

  it('手工保存规格（FR-ISSUE-06 兜底）：成功上抛 specSaved', async () => {
    const wrapper = mountDetail('SUBMITTED');
    await flushPromises();
    saveSpecMock.mockResolvedValue(spec);
    await wrapper.find('.spec-editor').setValue('{"schemaVersion":1}');
    await clickButton(wrapper, '保存规格');
    expect(saveSpecMock).toHaveBeenCalledWith('i1', '{"schemaVersion":1}');
    expect(wrapper.emitted('specSaved')?.[0]?.[0]).toMatchObject({ revision: 1 });
  });

  it('预览：合法规格展示资源清单', async () => {
    const wrapper = mountDetail('SUBMITTED');
    await flushPromises();
    previewMock.mockResolvedValue({
      valid: true,
      resources: { 'plugin.json': '{}', 'metadata/entities/demo.json': '{}' },
      errors: [],
    });
    await clickButton(wrapper, '预览插件资源');
    const preview = wrapper.find('[data-testid="spec-preview"]');
    expect(preview.text()).toContain('2 个资源文件');
    expect(preview.text()).toContain('plugin.json');
  });
});

describe('IssueDetail 生成（FR-ISSUE-05）', () => {
  beforeEach(() => {
    resetMocks();
    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
  });

  it('生成须确认：取消不调用', async () => {
    window.confirm = () => false;
    const wrapper = mountDetail('APPROVED');
    await flushPromises();
    await clickButton(wrapper, '生成并激活');
    expect(generateMock).not.toHaveBeenCalled();
  });

  it('生成确认后调用并展示结果（FR-ISSUE-05）', async () => {
    window.confirm = () => true;
    const wrapper = mountDetail('APPROVED');
    await flushPromises();
    generateMock.mockResolvedValue({
      pluginId: 'gen.iabc',
      version: '0.1.1',
      versionId: 'v2',
      activationId: 'a9',
      issue: { ...baseIssue, status: 'IN_TESTING' },
    });
    await clickButton(wrapper, '生成并激活');
    expect(generateMock).toHaveBeenCalledWith('i1');
    expect(wrapper.find('[data-testid="generate-outcome"]').text()).toContain('gen.iabc@0.1.1');
    expect(wrapper.emitted('updated')?.[0]?.[0]).toMatchObject({ status: 'IN_TESTING' });
  });
});

describe('IssueDetail 评论（FR-ISSUE-01）', () => {
  beforeEach(() => {
    resetMocks();
    commentsMock.mockResolvedValue([]);
    specMock.mockResolvedValue(null);
  });

  it('发表评论成功后刷新评论区', async () => {
    const wrapper = mountDetail();
    await flushPromises();
    addCommentMock.mockResolvedValue(undefined);
    commentsMock.mockResolvedValueOnce([
      { id: 'c1', issueId: 'i1', author: 'dev', body: '收到', createdAt: '2026-08-30T03:00:00Z' },
    ]);
    const textareas = wrapper.findAll('textarea');
    const commentBox = textareas[textareas.length - 1];
    await commentBox.setValue('收到');
    const submit = wrapper.findAll('button').find((b) => b.text() === '发表')!;
    await submit.trigger('click');
    await flushPromises();
    expect(addCommentMock).toHaveBeenCalledWith('i1', '收到');
    expect(wrapper.text()).toContain('收到');
  });
});
