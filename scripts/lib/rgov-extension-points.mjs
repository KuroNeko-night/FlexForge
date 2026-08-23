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

// 提取 Java 常量源码中的字符串常量值。值域不做白名单假设（任意非空字面量），
// 防止含数字/下划线/大写的未登记常量被静默跳过（审计 P2-2 fail-open 修复）。
export function extractConstantIds(source) {
  return [...source.matchAll(/public static final String [A-Z_]+ = "([^"]+)"/g)].map((m) => m[1]);
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

// 常驻负样本自检：解析器必须能抓出"值含数字/下划线的未登记常量"与非 active 行，
// 否则门禁 fail-open（固化为每次运行都执行的断言，不再依赖一次性人工验证）。
function parserSelfCheck() {
  const syntheticJava = [
    'public final class Sample {',
    '  public static final String META = "service.meta";',
    '  public static final String SUSPICIOUS = "service.evil_v2";',
    '  public static final String ALL = List.of(META);',
    '}',
  ].join('\n');
  const extracted = extractConstantIds(syntheticJava);
  if (!extracted.includes('service.evil_v2') || !extracted.includes('service.meta')) {
    return `常量提取负样本失败（应抓到 service.evil_v2）: ${JSON.stringify(extracted)}`;
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
