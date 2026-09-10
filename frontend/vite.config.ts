import { fileURLToPath, URL } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig, loadEnv } from 'vite';

// CSP 配置骨架（docs/13 §3.4）：生产预览启用基础策略；
// 开发模式不注入 CSP，Vite HMR 所需放宽仅限 dev（docs/13 P01/P06）。
const SECURITY_HEADERS: Record<string, string> = {
  'Content-Security-Policy':
    "default-src 'self'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'",
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
};

// P25 字体裁剪（交叉审查 P2-2）：剔除 @font-face src 中的 .woff 回退——现代浏览器
// 均取 woff2，.woff 纯死重（noto-sans-sc 全量 299 个 ≈9.5MB）。内联 PostCSS
// 插件零新依赖；只改字体 src 声明，不触碰其他值。
const stripWoffFallback = {
  postcssPlugin: 'flexforge-strip-woff-fallback',
  Declaration(decl: { prop: string; value: string }) {
    if (decl.prop === 'src' && decl.value.includes('.woff2')) {
      decl.value = decl.value.replace(/,\s*url\([^)]*\.woff\)\s*format\('[^']*'\)/g, '');
    }
  },
};

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  // 同源代理是 CORS 基线（docs/13 §3.8）：禁止 allowedOrigins("*")，前后端均走 Vite dev proxy。
  const proxyTarget = env.VITE_PROXY_TARGET || 'http://127.0.0.1:8080';

  return {
    plugins: [vue()],
    css: {
      postcss: {
        plugins: [stripWoffFallback],
      },
    },
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        '/api': { target: proxyTarget, changeOrigin: true },
        '/actuator': { target: proxyTarget, changeOrigin: true },
      },
    },
    preview: {
      headers: SECURITY_HEADERS,
    },
  };
});
