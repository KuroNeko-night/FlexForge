import fs from 'node:fs';
import path from 'node:path';

/**
 * R-GOV-09 骨架纯净性（NFR-SKEL-01，docs/09 P09 起）：后端 main 源码与前端 src
 * 不得硬编码演示插件标识（example.inventory / inventory_item / example_inventory）。
 * 骨架只能经插件注册获得业务能力；plugins/ 目录与测试代码不在扫描范围。
 */
const DEMO_PLUGIN_PATTERN = /example[._-]inventory|inventory_item/i;
const SCAN_PREFIXES = ['backend/', 'frontend/src/'];
const MAIN_SOURCE_ONLY = /\/src\/main\//;

export function checkRgov09(files, record) {
  const offenders = [];
  for (const file of files) {
    if (!SCAN_PREFIXES.some((p) => file.startsWith(p))) continue;
    if (!file.endsWith('.java') && !file.endsWith('.ts') && !file.endsWith('.vue')) continue;
    if (file.endsWith('.test.ts') || file.endsWith('.spec.ts')) continue;
    if (file.startsWith('backend/') && !MAIN_SOURCE_ONLY.test(`/${file}`)) continue;
    let content;
    try {
      content = fs.readFileSync(path.resolve(process.cwd(), file), 'utf8');
    } catch {
      continue;
    }
    if (DEMO_PLUGIN_PATTERN.test(content)) {
      offenders.push(file);
    }
  }
  if (offenders.length > 0) {
    record('R-GOV-09', 'fail', [
      `骨架纯净性违规：${offenders.length} 个骨架源码硬编码演示插件`,
      ...offenders,
    ]);
  } else {
    record('R-GOV-09', 'pass', ['骨架纯净性：骨架源码无演示插件硬编码（NFR-SKEL-01）']);
  }
}
