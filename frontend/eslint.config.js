import eslint from '@eslint/js';
import prettier from 'eslint-config-prettier';
import pluginVue from 'eslint-plugin-vue';
import globals from 'globals';
import tseslint from 'typescript-eslint';

// 硬上限（docs/coding-standards.md §7 唯一数值来源）：
// - TS/JS：文件 300 行、函数 50 行、复杂度 10、参数 5
// - Vue SFC：文件 350 行、复杂度 10（§7 对 SFC 不设函数/参数上限）
// 目标值（文件 200/250 行，warn 不阻断）见 eslint.config.targets.js。
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**'] },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    // 浏览器运行环境（P06 起组件/视图使用 DOM API；非放宽数值上限）
    languageOptions: { globals: { ...globals.browser } },
  },
  {
    files: ['**/*.ts', '**/*.tsx'],
    rules: {
      'max-lines': ['error', { max: 300, skipBlankLines: false, skipComments: false }],
      'max-lines-per-function': ['error', { max: 50 }],
      complexity: ['error', 10],
      'max-params': ['error', 5],
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
      },
    },
    rules: {
      'max-lines': ['error', { max: 350, skipBlankLines: false, skipComments: false }],
      complexity: ['error', 10],
    },
  },
  prettier,
);
