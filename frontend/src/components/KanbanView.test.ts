// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';

import type { EntityDetail, RecordView, ViewDefinition } from '@/api/types';
import { registerBuiltins } from '@/registry/rendererRegistry';
import KanbanView from '@/components/KanbanView.vue';

registerBuiltins();

/** P17 看板视图：groupBy 枚举选项为列、列头计数、未设置兜底列、卡片点击进详情。 */
function definition(): EntityDetail {
  return {
    id: 'e1',
    name: 'pipeline_task',
    displayName: '管线任务',
    status: 'enabled',
    pluginId: 'example.kanban',
    metaVersion: 1,
    fields: [
      {
        id: 'f1',
        name: 'title',
        displayName: '任务',
        fieldType: 'text',
        required: true,
        defaultValue: null,
        validation: null,
        rendererId: 'text.default',
        position: 0,
      },
      {
        id: 'f2',
        name: 'stage',
        displayName: '阶段',
        fieldType: 'enum',
        required: true,
        defaultValue: null,
        validation: { options: ['待办', '进行中', '已完成'] },
        rendererId: 'enum.default',
        position: 1,
      },
    ],
    views: [],
  };
}

const kanbanView: ViewDefinition = {
  id: 'v-k',
  viewType: 'kanban',
  name: '任务看板',
  columns: [{ field: 'title' }, { field: 'stage' }],
  filters: null,
  groupBy: 'stage',
};

function record(id: string, title: string, stage: string | null): RecordView {
  return { id, data: { title, stage } } as unknown as RecordView;
}

function mountBoard(records: RecordView[]) {
  return mount(KanbanView, {
    props: { definition: definition(), view: kanbanView, records },
  });
}

describe('KanbanView（P17 看板渲染）', () => {
  it('按枚举选项分列，列头计数正确，卡片点击上抛', async () => {
    const wrapper = mountBoard([
      record('r1', '接线文档', '进行中'),
      record('r2', '补测试', '进行中'),
      record('r3', '上线', '已完成'),
    ]);
    const cols = wrapper.findAll('.kanban-col');
    expect(cols.map((col) => col.find('.kanban-col-label').text())).toEqual([
      '待办',
      '进行中',
      '已完成',
    ]);
    const counts = cols.map((col) => col.find('.kanban-col-count').text());
    expect(counts).toEqual(['0', '2', '1']);
    await wrapper.find('[data-record="r1"]').trigger('click');
    expect(wrapper.emitted('card-click')?.[0]?.[0]).toMatchObject({ id: 'r1' });
  });

  it('分组值为空或不在选项内时落入未设置列', () => {
    const wrapper = mountBoard([record('r9', '野值任务', '已废弃'), record('r0', '无阶段', null)]);
    const labels = wrapper.findAll('.kanban-col-label').map((n) => n.text());
    expect(labels).toEqual(['待办', '进行中', '已完成', '未设置']);
    const unset = wrapper.findAll('.kanban-col').at(-1)!;
    expect(unset.find('.kanban-col-count').text()).toBe('2');
  });

  it('卡片字段来自视图 columns（首字段标题强调），空列显示空态', () => {
    const wrapper = mountBoard([record('r1', '接线文档', '待办')]);
    const card = wrapper.find('.kanban-card');
    expect(card.text()).toContain('接线文档');
    expect(card.text()).toContain('待办');
    const doneCol = wrapper.findAll('.kanban-col')[2];
    expect(doneCol.text()).toContain('暂无记录');
  });
});

describe('KanbanView（P19 拖拽换列）', () => {
  const dataTransfer = () => ({ setData: () => {}, dropEffect: '' });

  async function dragTo(wrapper: ReturnType<typeof mountBoard>, fromRecord: string, toIndex: number) {
    const card = wrapper.find(`[data-record="${fromRecord}"]`);
    await card.trigger('dragstart', { dataTransfer: dataTransfer() });
    const target = wrapper.findAll('.kanban-col')[toIndex]!;
    await target.trigger('dragover', { dataTransfer: dataTransfer() });
    await target.trigger('drop', { dataTransfer: dataTransfer() });
    return target;
  }

  it('拖到其他枚举列上抛 card-move（记录+目标选项），拖起卡片带拖拽态', async () => {
    const wrapper = mountBoard([record('r1', '接线文档', '待办')]);
    const card = wrapper.find('[data-record="r1"]');
    expect(card.attributes('draggable')).toBe('true');
    await card.trigger('dragstart', { dataTransfer: dataTransfer() });
    expect(card.classes()).toContain('is-dragging');
    const doneCol = wrapper.findAll('.kanban-col')[2]!;
    await doneCol.trigger('dragover', { dataTransfer: dataTransfer() });
    expect(doneCol.classes()).toContain('is-drop-target');
    await doneCol.trigger('drop', { dataTransfer: dataTransfer() });
    const moves = wrapper.emitted('card-move');
    expect(moves).toHaveLength(1);
    expect(moves![0]![0]).toMatchObject({ id: 'r1' });
    expect(moves![0]![1]).toBe('已完成');
    // 会话结束清理拖拽态
    expect(card.classes()).not.toContain('is-dragging');
    expect(doneCol.classes()).not.toContain('is-drop-target');
  });

  it('拖回源所在列不产生移动事件（dragover 不放行）', async () => {
    const wrapper = mountBoard([record('r1', '接线文档', '进行中')]);
    const sameCol = await dragTo(wrapper, 'r1', 1);
    expect(sameCol.classes()).not.toContain('is-drop-target');
    expect(wrapper.emitted('card-move')).toBeUndefined();
  });

  it('未设置兜底列不可作落点（枚举外值可拖出，不可拖入）', async () => {
    const wrapper = mountBoard([record('r9', '野值任务', '已废弃')]);
    const labels = wrapper.findAll('.kanban-col-label').map((n) => n.text());
    expect(labels).toEqual(['待办', '进行中', '已完成', '未设置']);
    const unsetCol = wrapper.findAll('.kanban-col')[3]!;
    expect(unsetCol.classes()).toContain('is-unset-col');
    await dragTo(wrapper, 'r9', 0);
    expect(wrapper.emitted('card-move')).toHaveLength(1);
  });

  it('dragend 清理会话（无 drop 也不残留拖拽态）', async () => {
    const wrapper = mountBoard([record('r1', '接线文档', '待办')]);
    const card = wrapper.find('[data-record="r1"]');
    await card.trigger('dragstart', { dataTransfer: dataTransfer() });
    await card.trigger('dragend');
    expect(card.classes()).not.toContain('is-dragging');
    expect(wrapper.emitted('card-move')).toBeUndefined();
  });
});
