// R-GOV-03 门禁（docs/11 §3，P02 激活）：代码常量集合 == docs/extension-points.md
// active 集合，且每个 kind 的注册-撤销测试存在且全部通过。
// 独立成模块以控制 gates.mjs 体积（审计 P3-3）；解析器带常驻负样本自检（审计 P2-2）。
import fs from 'node:fs';
import path from 'node:path';

import { BACKEND, ROOT, surefireReport } from './gates.mjs';

const RGOV03_KINDS = [
  ['ServiceKey', 'ServiceKeys.java'],
  ['ExtensionPoint', 'ExtensionPoints.java'],
  ['DomainEvent', 'DomainEventTypes.java'],
];

const RGOV03_REGISTRY_TESTS = [
  ['ServiceRegistryTest', 'ServiceKey'],
  ['ExtensionRegistryTest', 'ExtensionPoint'],
  ['DomainEventPublisherTest', 'DomainEvent'],
];

// 提取 Java 常量源码中的字符串常量值。值域/名字域均不做白名单假设：
// 名字允许数字（复审 P3-2：Checkstyle ConstantName 允许 [A-Z][A-Z0-9_]*），
// 修饰符与等号两侧空白宽松，值匹配任意非空字面量——防止未登记常量被静默跳过。
export const CONSTANT_PATTERN =
  /public\s+static\s+final\s+String\s+([A-Z][A-Z0-9_]*)\s*=\s*"([^"]+)"/g;

export function extractConstantIds(source) {
  return [...source.matchAll(CONSTANT_PATTERN)].map((m) => m[2]);
}

// 名称 → 值映射，用于校验 ALL 集合覆盖全部常量（复审 P3-4）。
export function extractConstantNameToValue(source) {
  const map = new Map();
  for (const m of source.matchAll(CONSTANT_PATTERN)) {
    map.set(m[1], m[2]);
  }
  return map;
}

// 提取 ALL = List.of(...) 引用的常量名集合（复审 P3-4：ALL 覆盖性校验）。
export function extractAllListNames(source) {
  const list = source.match(/List\.of\(([^)]*)\)/);
  if (!list) {
    return [];
  }
  return list[1].split(',').map((name) => name.trim()).filter(Boolean);
}

// 解析登记册 §2.1-2.3 表格中状态为 active 的 ID（首列反引号，尾列为状态）。
export function parseRegistryActiveIds(md) {
  const result = {};
  for (const [kind, sectionRe] of [
    ['ServiceKey', /### 2\.1 ServiceKey([\s\S]*?)(?=### 2\.2)/],
    ['ExtensionPoint', /### 2\.2 ExtensionPoint([\s\S]*?)(?=### 2\.3)/],
    ['DomainEvent', /### 2\.3 DomainEvent([\s\S]*?)(?=### 2\.4)/],
  ]) {
    const body = md.match(sectionRe)?.[1] ?? '';
    result[kind] = new Set(
      body.split('\n')
        .map((line) => line.split('|').map((cell) => cell.trim()))
        .filter((cells) => cells.length >= 4 && cells[1].startsWith('`'))
        .filter((cells) => cells[cells.length - 2] === 'active')
        .map((cells) => cells[1].replace(/`/g, '')),
    );
  }
  return result;
}

// 常驻负样本自检：解析器必须能抓出"值含数字/下划线""名字含数字""修饰符对齐空格"的
// 未登记常量与非 active 行；ALL 集合覆盖性校验必须在位——否则门禁 fail-open
// （固化为每次运行都执行的断言，不再依赖一次性人工验证）。
function parserSelfCheck() {
  const syntheticJava = [
    'public final class Sample {',
    '  public  static final String META = "service.meta";',
    '  public static final String SUSPICIOUS = "service.evil_v2";',
    '  public static final String EVIL_V2 = "service.evil2";',
    '  public static final List<String> ALL = List.of(META);',
    '}',
  ].join('\n');
  const extracted = extractConstantIds(syntheticJava);
  for (const expected of ['service.meta', 'service.evil_v2', 'service.evil2']) {
    if (!extracted.includes(expected)) {
      return `常量提取负样本失败（应抓到 ${expected}）: ${JSON.stringify(extracted)}`;
    }
  }
  const syntheticMd = [
    '### 2.1 ServiceKey',
    '',
    '| ID | 契约 | 责任模块 | 消费方 | 引入阶段 | 状态 |',
    '| --- | --- | --- | --- | --- | --- |',
    '| `service.meta` | x | x | x | x | active |',
    '| `service.old` | x | x | x | x | deprecated |',
    '| `service.plan` | x | x | x | x | proposed |',
    '',
    '### 2.2 ExtensionPoint',
    '### 2.3 DomainEvent',
    '### 2.4 受控契约',
  ].join('\n');
  const parsed = parseRegistryActiveIds(syntheticMd);
  const active = [...parsed.ServiceKey];
  if (active.length !== 1 || active[0] !== 'service.meta') {
    return `登记册解析负样本失败（应只取 active 的 service.meta）: ${JSON.stringify(active)}`;
  }
  return null;
}

// 单文件级校验：ALL 集合必须引用全部 String 常量（复审 P3-4），
// 防止"常量与登记册一致但漏加 ALL"导致运行时误拒已登记 ID。
function allCoverageProblem(source, fileName) {
  const nameToValue = extractConstantNameToValue(source);
  const allNames = extractAllListNames(source);
  const allValues = new Set(allNames.map((name) => nameToValue.get(name)));
  const constantValues = new Set(nameToValue.values());
  const missing = [...constantValues].filter((v) => !allValues.has(v));
  if (allNames.length === 0) {
    return `${fileName} 未找到 ALL = List.of(...) 声明`;
  }
  if (missing.length > 0 || allValues.size !== constantValues.size) {
    return `${fileName} ALL 集合与 String 常量不一致: ${JSON.stringify([...constantValues])} vs ${JSON.stringify([...allValues])}`;
  }
  return null;
}

export function checkRgov03(backend, record) {
  if (backend.skipped) {
    record('R-GOV-03', 'skip', ['后端未初始化']);
    return;
  }
  const selfCheckProblem = parserSelfCheck();
  if (selfCheckProblem) {
    record('R-GOV-03', 'fail', [`解析器自检失败（门禁不可信）: ${selfCheckProblem}`]);
    return;
  }
  const problems = [];
  const counts = [];
  try {
    const registry = parseRegistryActiveIds(
      fs.readFileSync(path.join(ROOT, 'docs', 'extension-points.md'), 'utf8'),
    );
    const code = {};
    for (const [kind, fileName] of RGOV03_KINDS) {
      const source = fs.readFileSync(
        path.join(BACKEND, 'flexforge-common', 'src', 'main', 'java',
          'com', 'flexforge', 'common', 'registry', fileName),
        'utf8',
      );
      const coverageProblem = allCoverageProblem(source, fileName);
      if (coverageProblem) {
        problems.push(coverageProblem);
      }
      code[kind] = new Set(extractConstantIds(source));
    }
    for (const kind of Object.keys(registry)) {
      const registryOnly = [...registry[kind]].filter((id) => !code[kind].has(id));
      const codeOnly = [...code[kind]].filter((id) => !registry[kind].has(id));
      if (registryOnly.length > 0) problems.push(`${kind} 登记册有而代码缺失: ${registryOnly.join(', ')}`);
      if (codeOnly.length > 0) problems.push(`${kind} 代码有而登记册未登记: ${codeOnly.join(', ')}`);
      counts.push(`${kind} ${registry[kind].size}`);
    }
  } catch (e) {
    record('R-GOV-03', 'fail', [`登记册/常量解析失败: ${e.message}`]);
    return;
  }
  for (const [testName] of RGOV03_REGISTRY_TESTS) {
    const report = surefireReport(testName, 'flexforge-runtime', 'com.flexforge.runtime');
    if (report === null || report.tests < 1 || report.errors > 0 || report.failures > 0) {
      problems.push(`注册-撤销测试未通过或报告缺失: ${testName}`);
    }
  }
  record('R-GOV-03', problems.length ? 'fail' : 'pass',
    problems.length ? problems
      : [`常量集合 == 登记册 active（${counts.join(' / ')}）；解析器负样本自检通过`,
        '注册-撤销测试通过（ServiceRegistryTest/ExtensionRegistryTest/DomainEventPublisherTest）']);
}
