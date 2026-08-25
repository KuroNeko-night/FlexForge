import { describe, expect, it } from 'vitest';

import { KeyedRegistry } from '@/registry/keyed';

describe('KeyedRegistry（五扩展点共用语义，docs/extension-points §1）', () => {
  it('注册后可解析，后注册覆盖先注册', () => {
    const registry = new KeyedRegistry<string>();
    registry.register('a', '1');
    expect(registry.resolve('a')).toBe('1');
    registry.register('a', '2');
    expect(registry.resolve('a')).toBe('2');
  });

  it('close 幂等且防陈旧：旧 Registration 不会撤掉新值', () => {
    const registry = new KeyedRegistry<string>();
    const first = registry.register('a', '1');
    registry.register('a', '2');
    first.close();
    expect(registry.resolve('a')).toBe('2');
    first.close();
    expect(registry.resolve('a')).toBe('2');
  });

  it('close 只移除自己的键', () => {
    const registry = new KeyedRegistry<string>();
    const reg = registry.register('a', '1');
    registry.register('b', '2');
    reg.close();
    expect(registry.resolve('a')).toBeUndefined();
    expect(registry.resolve('b')).toBe('2');
  });

  it('按 activationId 批量撤销不残留，未关联的保留', () => {
    const registry = new KeyedRegistry<string>();
    registry.register('menu.x', 'x', 'act-1');
    registry.register('menu.y', 'y', 'act-1');
    registry.register('menu.z', 'z', null);
    expect(registry.revokeByActivation('act-1')).toBe(2);
    expect(registry.resolve('menu.x')).toBeUndefined();
    expect(registry.resolve('menu.y')).toBeUndefined();
    expect(registry.resolve('menu.z')).toBe('z');
  });

  it('覆盖后按 activationId 撤销不误删新批次（插件重激活语义）', () => {
    const registry = new KeyedRegistry<string>();
    registry.register('menu.x', 'old', 'act-1');
    registry.register('menu.x', 'new', 'act-2');
    expect(registry.revokeByActivation('act-1')).toBe(0);
    expect(registry.resolve('menu.x')).toBe('new');
    expect(registry.revokeByActivation('act-2')).toBe(1);
    expect(registry.resolve('menu.x')).toBeUndefined();
  });

  it('list 按注册序快照', () => {
    const registry = new KeyedRegistry<string>();
    registry.register('b', '2');
    registry.register('a', '1');
    expect(registry.list().map((entry) => entry.key)).toEqual(['b', 'a']);
  });
});
