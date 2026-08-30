// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import type { AiConfigView } from '@/api/settings';
import { clearSession, saveSession } from '@/auth/token';
import SettingsView from '@/views/SettingsView.vue';

vi.mock('@/api/settings', () => ({
  fetchAiConfig: vi.fn(),
  updateAiConfig: vi.fn(),
}));

import { fetchAiConfig, updateAiConfig } from '@/api/settings';

const fetchMock = vi.mocked(fetchAiConfig);
const updateMock = vi.mocked(updateAiConfig);

/** 测试占位值（非真实凭据，低熵）：模拟用户在密码框输入的新密钥。 */
const TYPED_SECRET = 'demo-typed-secret-value';

const view = (overrides: Partial<AiConfigView> = {}): AiConfigView => ({
  provider: 'fixture',
  baseUrl: '',
  model: 'gpt-4o-mini',
  apiKeyConfigured: false,
  apiKeyHint: null,
  apiKeyStale: false,
  ...overrides,
});

function loginAdmin(): void {
  saveSession('t', { id: 1, username: 'admin', displayName: 'A', roles: ['ADMIN'] });
}

beforeEach(() => {
  fetchMock.mockReset();
  updateMock.mockReset();
});

describe('SettingsView AI 配置读取（ADMIN，FR-SETUP-01）', () => {
  it('渲染当前配置：掩码提示在占位符中且不含明文', async () => {
    loginAdmin();
    fetchMock.mockResolvedValue(
      view({
        provider: 'http',
        baseUrl: 'https://api.example/v1',
        model: 'demo',
        apiKeyConfigured: true,
        apiKeyHint: '…9876',
      }),
    );
    const wrapper = mount(SettingsView);
    await flushPromises();
    const keyInput = wrapper.find('input[name="apiKey"]').element as HTMLInputElement;
    expect(keyInput.placeholder).toContain('已配置（…9876）');
    expect(keyInput.placeholder).toContain('留空保持不变');
    expect(keyInput.value).toBe('');
    expect((wrapper.find('input[name="baseUrl"]').element as HTMLInputElement).value).toBe(
      'https://api.example/v1',
    );
  });

  it('密钥轮换提示（apiKeyStale 显示重新录入告警）', async () => {
    loginAdmin();
    fetchMock.mockResolvedValue(
      view({ apiKeyConfigured: true, apiKeyHint: '…9876', apiKeyStale: true }),
    );
    const wrapper = mount(SettingsView);
    await flushPromises();
    expect(wrapper.text()).toContain('无法解密');
    clearSession();
  });
});

describe('SettingsView AI 配置保存（FR-SETUP-01）', () => {
  it('输入的密钥原样上送、保存后输入框清空', async () => {
    loginAdmin();
    fetchMock.mockResolvedValue(view());
    const wrapper = mount(SettingsView);
    await flushPromises();
    updateMock.mockResolvedValue(
      view({ provider: 'http', apiKeyConfigured: true, apiKeyHint: '…abcd' }),
    );
    await wrapper.find('[data-testid="provider-select"]').setValue('http');
    await wrapper.find('input[name="baseUrl"]').setValue('https://api.example/v1');
    await wrapper.find('input[name="model"]').setValue('demo-model');
    await wrapper.find('input[name="apiKey"]').setValue(TYPED_SECRET);
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(updateMock).toHaveBeenCalledWith({
      provider: 'http',
      baseUrl: 'https://api.example/v1',
      model: 'demo-model',
      apiKey: TYPED_SECRET,
      clearApiKey: undefined,
    });
    expect((wrapper.find('input[name="apiKey"]').element as HTMLInputElement).value).toBe('');
    expect(wrapper.text()).toContain('已保存，配置即时生效');
  });

  it('失败呈现可诊断错误含 requestId（服务端校验 400）', async () => {
    loginAdmin();
    fetchMock.mockResolvedValue(view());
    const wrapper = mount(SettingsView);
    await flushPromises();
    updateMock.mockRejectedValue(
      new ApiError('validation_error', 'provider=http 时 base-url 必填', 400, 'req-7'),
    );
    await wrapper.find('form').trigger('submit');
    await flushPromises();
    expect(wrapper.text()).toContain('base-url 必填');
    expect(wrapper.text()).toContain('req-7');
    clearSession();
  });
});

describe('SettingsView 非 ADMIN', () => {
  it('不加载 AI 配置也不渲染表单（服务端边界 + 前端只控显隐）', async () => {
    saveSession('t', { id: 2, username: 'u', displayName: 'U', roles: ['USER'] });
    const wrapper = mount(SettingsView);
    await flushPromises();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="ai-config-form"]').exists()).toBe(false);
    clearSession();
  });

  it('403 显示无权限态', async () => {
    saveSession('t', { id: 2, username: 'u', displayName: 'U', roles: ['ADMIN'] });
    fetchMock.mockRejectedValue(new ApiError('forbidden', '无权限', 403, null));
    const wrapper = mount(SettingsView);
    await flushPromises();
    expect(wrapper.find('[data-state="denied"]').exists()).toBe(true);
    clearSession();
  });
});
