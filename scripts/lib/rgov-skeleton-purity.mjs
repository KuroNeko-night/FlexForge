import fs from 'node:fs';
import path from 'node:path';
import { ROOT } from './gates.mjs';

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
  // file 是仓库根相对路径（与 git ls-files 输出一致）；路径锚定 gates.mjs 的 ROOT
  // 而非 process.cwd()——否则从子目录运行时 read 全部失败被 continue 吞掉，
  // 扫描 0 个文件却报 pass（fail-open，注释审计 P2 修复）
  for (const file of files) {
    if (!SCAN_PREFIXES.some((p) => file.startsWith(p))) continue;
    if (!file.endsWith('.java') && !file.endsWith('.ts') && !file.endsWith('.vue')) continue;
    if (file.endsWith('.test.ts') || file.endsWith('.spec.ts')) continue;
    if (file.startsWith('backend/') && !MAIN_SOURCE_ONLY.test(`/${file}`)) continue;
    let content;
    try {
      content = fs.readFileSync(path.join(ROOT, file), 'utf8');
    } catch (err) {
      // 单文件读失败必须可见：静默 continue 会让"扫描不到"伪装成"扫描通过"
      record('R-GOV-09', 'fail', [`骨架纯净性扫描读取失败: ${file}（${err.code ?? err.message}）`]);
      return;
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
