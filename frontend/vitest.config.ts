import { fileURLToPath, URL } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vitest/config';

// vitest.config 优先于 vite.config（vitest 约定），alias 与 vite.config 保持一致；
// 默认 node 环境（registry/客户端单测），DOM 组件测试用 @vitest-environment happy-dom 文件级标注。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
