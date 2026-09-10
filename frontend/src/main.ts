import { createApp } from 'vue';

import App from './App.vue';
import { onUnauthorized } from './api/client';
import { clearSession } from './auth/token';
import { registerBuiltinContributions } from './registry/builtinContributions';
import { registerBuiltins } from './registry/rendererRegistry';
import { router } from './router';
import '@fontsource/inter/latin-400.css';
import '@fontsource/inter/latin-600.css';
import '@fontsource/inter/latin-700.css';
/* P25 字体系统：display=Space Grotesk（标题/品牌/数字），中文正文=Noto Sans SC
   （unicode-range 分片，浏览器按需拉取）；均自托管无运行时外链（CSP 兼容） */
import '@fontsource/space-grotesk/latin-500.css';
import '@fontsource/space-grotesk/latin-700.css';
import '@fontsource/noto-sans-sc/400.css';
import '@fontsource/noto-sans-sc/500.css';
import '@fontsource/noto-sans-sc/700.css';
import './styles/tokens.css';
import './styles/base.css';

const app = createApp(App);

// 内置注册必须先于 mount：registry 是模块级数据，首帧渲染即同步解析
// renderer/widget，晚注册首屏会落到回退组件；两个 register 均幂等（HMR 安全）
registerBuiltins();
registerBuiltinContributions();
onUnauthorized(() => {
  clearSession();
  void router.push({ name: 'login' });
});

app.use(router);
app.mount('#app');
