import tseslint from 'typescript-eslint';
import vueParser from 'vue-eslint-parser';

// 目标值（docs/coding-standards.md §7）：文件长度建议目标 TS/JS 200、Vue 250。
// 本配置只产出 warn，不阻断；CI 硬上限以 eslint.config.js 为准。
export default [
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**'] },
  {
    files: ['**/*.ts', '**/*.tsx'],
    languageOptions: { parser: tseslint.parser },
    rules: {
      'max-lines': ['warn', { max: 200, skipBlankLines: false, skipComments: false }],
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: { parser: tseslint.parser },
    },
    rules: {
      'max-lines': ['warn', { max: 250, skipBlankLines: false, skipComments: false }],
    },
  },
];
