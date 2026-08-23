// R-GOV-01 简化版（docs/11 §3）：解析 Checkstyle/ESLint 配置中的阈值，
// 与 docs/coding-standards.md §7 表格比对。docs 是唯一数值来源，任何漂移都报 FAIL。
// P02 升级为完整解析断言（含注释、suppression 与多文件合并语义）。
import fs from 'node:fs';
import path from 'node:path';

const isPresent = (value) => Number.isInteger(value);

function sectionText(root) {
  const text = fs.readFileSync(path.join(root, 'docs', 'coding-standards.md'), 'utf8');
  const start = text.indexOf('## 7.');
  if (start < 0) {
    throw new Error('docs/coding-standards.md 缺少 §7 章节');
  }
  return text.slice(start);
}

function tableRow(text, label) {
  const row = text
    .split('\n')
    .find((line) => line.includes(label));
  if (!row) {
    throw new Error(`docs/coding-standards.md §7 缺少“${label}”行`);
  }
  return row;
}

function parseDocsThresholds(root) {
  const text = sectionText(root);
  const file = tableRow(text, '单文件长度').match(
    /\|\s*(\d+)\s*\/\s*(\d+)\s*行\s*\|\s*(\d+)\s*\/\s*(\d+)\s*行\s*\|\s*(\d+)\s*\/\s*(\d+)\s*行/,
  );
  const method = tableRow(text, '函数/方法长度').match(
    /\|\s*≤\s*(\d+)\s*行\s*\|\s*—\s*\|\s*≤\s*(\d+)\s*行/,
  );
  const complexity = tableRow(text, '圈复杂度').match(
    /\|\s*≤\s*(\d+)\s*\|\s*≤\s*(\d+)/,
  );
  const params = tableRow(text, '参数个数').match(
    /\|\s*≤\s*(\d+)\s*\|\s*—\s*\|\s*≤\s*(\d+)/,
  );
  const nested = tableRow(text, '嵌套深度').match(/\|\s*≤\s*(\d+)/);
  if (!file || !method || !complexity || !params || !nested) {
    throw new Error('docs/coding-standards.md §7 表格格式无法解析');
  }
  const number = (value) => Number.parseInt(value, 10);
  return {
    backend: {
      fileHard: number(file[1]),
      fileTarget: number(file[2]),
      method: number(method[1]),
      complexity: number(complexity[1]),
      params: number(params[1]),
      nested: number(nested[1]),
    },
    ts: {
      fileHard: number(file[5]),
      fileTarget: number(file[6]),
      method: number(method[2]),
      complexity: number(complexity[2]),
      params: number(params[2]),
    },
    vue: {
      fileHard: number(file[3]),
      fileTarget: number(file[4]),
      complexity: number(complexity[2]),
    },
  };
}

function checkstyleModules(xml, name) {
  const pattern = new RegExp(`<module name="${name}">([\\s\\S]*?)</module>`, 'g');
  return [...xml.matchAll(pattern)].map((match) => match[1]);
}

function checkstyleProperty(moduleXml, key, fallback = null) {
  const match = moduleXml.match(new RegExp(`<property name="${key}" value="([^"]+)"`));
  return match ? match[1] : fallback;
}

function parseCheckstyle(root) {
  const xml = fs.readFileSync(path.join(root, 'backend', 'config', 'checkstyle.xml'), 'utf8');
  const files = checkstyleModules(xml, 'FileLength').map((module) => ({
    max: Number.parseInt(checkstyleProperty(module, 'max', '0'), 10),
    severity: checkstyleProperty(module, 'severity', 'error'),
  }));
  const hard = files.find((entry) => entry.severity === 'error');
  const target = files.find((entry) => entry.severity === 'warning');
  const single = (name) => checkstyleModules(xml, name)[0] || '';
  const max = (name) => Number.parseInt(checkstyleProperty(single(name), 'max', '0'), 10);
  return {
    fileHard: hard?.max ?? null,
    fileTarget: target?.max ?? null,
    method: max('MethodLength'),
    complexity: max('CyclomaticComplexity'),
    params: max('ParameterNumber'),
    nested: max('NestedIfDepth'),
  };
}

function eslintBlock(text, blockStart) {
  const start = text.indexOf(blockStart);
  if (start < 0) {
    return null;
  }
  // 提取到 rules 对象闭合处：配置文件内的 languageOptions/parserOptions 也有 `},`，
  // 若直接找第一个闭合会把 rules 截掉（Vue 块就是这种情况）。
  const rulesStart = text.indexOf('rules:', start);
  if (rulesStart < 0) {
    return null;
  }
  const end = text.indexOf('\n    },', rulesStart);
  return text.slice(start, end < 0 ? text.length : end);
}

// 直接从配置文本提取：max-lines 规则带对象选项，其余规则兼容 ['error', n] 与 { max: n }。
function eslintFileMax(block) {
  const match = block?.match(/'max-lines':\s*\['[a-z]+',\s*\{\s*max:\s*(\d+)/);
  return match ? Number.parseInt(match[1], 10) : null;
}

function eslintRuleNumber(block, rule) {
  const quoted = rule.replace(/[-/\\^$*+?.()|[\]{}]/g, '\\$&');
  const numberMatch = block?.match(
    new RegExp(`['"]?${quoted}['"]?:\\s*\\['error',\\s*(\\d+)`),
  );
  if (numberMatch) {
    return Number.parseInt(numberMatch[1], 10);
  }
  const objectMatch = block?.match(
    new RegExp(`['"]?${quoted}['"]?:\\s*\\['error',\\s*\\{\\s*max:\\s*(\\d+)`),
  );
  return objectMatch ? Number.parseInt(objectMatch[1], 10) : null;
}

function parseEslint(root) {
  const hard = fs.readFileSync(path.join(root, 'frontend', 'eslint.config.js'), 'utf8');
  const target = fs.readFileSync(path.join(root, 'frontend', 'eslint.config.targets.js'), 'utf8');
  const tsBlock = eslintBlock(hard, "files: ['**/*.ts', '**/*.tsx']");
  const vueBlock = eslintBlock(hard, "files: ['**/*.vue']");
  const tsTarget = eslintBlock(target, "files: ['**/*.ts', '**/*.tsx']");
  const vueTarget = eslintBlock(target, "files: ['**/*.vue']");
  return {
    ts: {
      fileHard: eslintFileMax(tsBlock),
      fileTarget: eslintFileMax(tsTarget),
      method: eslintRuleNumber(tsBlock, 'max-lines-per-function'),
      complexity: eslintRuleNumber(tsBlock, 'complexity'),
      params: eslintRuleNumber(tsBlock, 'max-params'),
    },
    vue: {
      fileHard: eslintFileMax(vueBlock),
      fileTarget: eslintFileMax(vueTarget),
      complexity: eslintRuleNumber(vueBlock, 'complexity'),
    },
  };
}

function compare(prefix, expected, actual, keys) {
  const mismatches = [];
  for (const key of keys) {
    if (isPresent(expected[key]) && expected[key] !== actual[key]) {
      mismatches.push(`${prefix}.${key}: docs=${expected[key]} config=${actual[key]}`);
    }
  }
  return mismatches;
}

export function checkLintThresholds(root) {
  const docs = parseDocsThresholds(root);
  const checkstyle = parseCheckstyle(root);
  const eslint = parseEslint(root);

  const mismatches = [
    ...compare('backend', docs.backend, checkstyle, [
      'fileHard',
      'fileTarget',
      'method',
      'complexity',
      'params',
      'nested',
    ]),
    ...compare('ts', docs.ts, eslint.ts, ['fileHard', 'fileTarget', 'method', 'complexity', 'params']),
    ...compare('vue', docs.vue, eslint.vue, ['fileHard', 'fileTarget', 'complexity']),
  ];

  if (mismatches.length > 0) {
    return {
      status: 'fail',
      detail: mismatches,
    };
  }
  return {
    status: 'pass',
    detail: [
      `阈值与 docs/coding-standards.md §7 一致：backend=${docs.backend.fileHard}/${docs.backend.fileTarget},${docs.backend.method},${docs.backend.complexity},${docs.backend.params},${docs.backend.nested}; ` +
        `ts=${docs.ts.fileHard}/${docs.ts.fileTarget},${docs.ts.method},${docs.ts.complexity},${docs.ts.params}; ` +
        `vue=${docs.vue.fileHard}/${docs.vue.fileTarget},${docs.vue.complexity}`,
    ],
  };
}
