// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '@/api/client';
import { session } from '@/auth/token';
import LoginView from '@/views/LoginView.vue';

const pushMock = vi.fn();

vi.mock('vue-router', () => ({ useRouter: () => ({ push: pushMock }) }));
vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  register: vi.fn(),
  fetchRegistrationStatus: vi.fn(),
}));

import { fetchRegistrationStatus, login, register } from '@/api/auth';

const statusMock = vi.mocked(fetchRegistrationStatus);
const registerMock = vi.mocked(register);
const loginMock = vi.mocked(login);

const authUser = { id: 7, username: 'newbie', displayName: '新人', roles: ['USER'] };

/** 切到注册模式并提交（三个用例共用的驱动序列）。 */
async function submitRegister(password: string): Promise<ReturnType<typeof mount>> {
  const wrapper = mount(LoginView);
  await flushPromises();
  await wrapper.find('a').trigger('click');
  await wrapper.find('input[name="username"]').setValue('newbie');
  await wrapper.find('input[name="password"]').setValue(password);
  wrapper.find('form').trigger('submit');
  await flushPromises();
  return wrapper;
}

describe('LoginView 登录/注册切换（P13）', () => {
  beforeEach(() => {
    statusMock.mockReset();
    registerMock.mockReset();
    loginMock.mockReset();
    pushMock.mockReset();
    session.token = null;
    session.user = null;
  });

  it('注册开放时显示入口，切换后注册成功即保存会话跳首页', async () => {
    statusMock.mockResolvedValue({ selfRegistrationEnabled: true });
    registerMock.mockResolvedValue({
      token: 'tk-reg',
      expiresAt: '',
      user: authUser,
    });
    const probe = mount(LoginView);
    await flushPromises();
    expect(probe.text()).toContain('自助注册');
    probe.unmount();
    await submitRegister('Whatever-Pass-9');
    expect(registerMock).toHaveBeenCalledWith('newbie', 'Whatever-Pass-9', 'newbie');
    expect(session.token).toBe('tk-reg');
    expect(session.user?.username).toBe('newbie');
  });

  it('注册关闭时隐藏入口（P13 验收：flag 关主流程不受影响）', async () => {
    statusMock.mockResolvedValue({ selfRegistrationEnabled: false });
    const wrapper = mount(LoginView);
    await flushPromises();
    expect(wrapper.text()).not.toContain('自助注册');
    expect(wrapper.find('a').exists()).toBe(false);
  });

  it('注册失败在表单内提示（不跳转）', async () => {
    statusMock.mockResolvedValue({ selfRegistrationEnabled: true });
    registerMock.mockRejectedValue(new ApiError('validation_error', '用户名已存在', 400, null));
    const wrapper = await submitRegister('Whatever-Pass-9');
    expect(wrapper.text()).toContain('用户名已存在');
    expect(pushMock).not.toHaveBeenCalled();
    expect(session.token).toBeNull();
    expect(loginMock).not.toHaveBeenCalled();
  });
});
