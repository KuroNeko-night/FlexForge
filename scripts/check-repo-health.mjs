#!/usr/bin/env node
// FlexForge 仓库健康检查（本地与 CI 共用入口，P02 起全量版）。
// 一条命令完成：文档/状态镜像检查、格式、前端 lint/type-check/test/build/audit、
// 后端 verify（Checkstyle + ArchUnit + Testcontainers 冒烟）、R-GOV-01/02/03/06。
// 编号对齐 docs/11-regression-test-plan.md §3；未到交付阶段的项输出 SKIP。
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  checkRgov02,
  checkRgov06,
  checkRgov08,
  runBackendVerify,
  runFrontendGates,
} from './lib/gates.mjs';
import { checkRgov03 } from './lib/rgov-extension-points.mjs';
import { checkLintThresholds } from './lib/rgov-thresholds.mjs';
import { buildStatusJson, readStatusSnapshot } from './lib/status.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const results = [];
const record = (id, status, detail) => results.push({ id, status, detail });

// 工作区文件集 = 已跟踪 + 未跟踪且未忽略的文件（新写未提交的文件也参与检查，
// 提交前运行才能拦住问题；.mimosa/ 等本地工具目录由 .gitignore 排除）
function workspaceFiles() {
  try {
    return execFileSync(
      'git',
      ['ls-files', '--cached', '--others', '--exclude-standard'],
      { cwd: ROOT, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 },
    )
      .split('\n')
      .filter(Boolean);
  } catch {
    console.error('无法执行 git ls-files：请在仓库内运行本脚本');
    process.exit(1);
  }
}

// R-GOV-05a：全部 Markdown 相对链接可解析
function checkLinks(files) {
  const broken = [];
  let total = 0;
  for (const f of files.filter((f) => f.endsWith('.md'))) {
    const text = fs.readFileSync(path.join(ROOT, f), 'utf8');
    for (const raw of text.match(/\]\(([^)]+)\)/g) || []) {
      const target = raw.slice(2, -1).split('#')[0];
      if (!target || /^[a-z]+:/i.test(target) || target.startsWith('<')) continue;
      total += 1;
      const resolved = path.posix.join(path.posix.dirname(f), target);
      if (!fs.existsSync(path.join(ROOT, resolved))) broken.push(`${f} -> ${target}`);
    }
  }
  record('R-GOV-05a', broken.length ? 'fail' : 'pass',
    broken.length ? broken : [`${total} 个相对链接全部可解析`]);
}

// R-GOV-05b：project-index §1“当前结构”树中列出的文件必须存在
function checkIndexTree(files) {
  const idx = fs.readFileSync(path.join(ROOT, 'docs', 'project-index.md'), 'utf8');
  const section1 = idx.split(/^## 2\./m)[0];
  const tree = (section1.match(/```text\n([\s\S]*?)```/) || [])[1] || '';
  const names = new Set(files.map((f) => path.posix.basename(f)));
  const missing = [];
  for (const token of tree.split(/\s+/)) {
    if (token.endsWith('/')) continue; // 目录条目只做结构提示，不检查存在性
    const t = token.replace(/^[|├└─\s]+/, '');
    const isFile = /^[\w.-]+\.[\w-]+$/.test(t) || /^\.[\w-]+$/.test(t);
    if (isFile && !names.has(t)) missing.push(t);
  }
  record('R-GOV-05b', missing.length ? 'fail' : 'pass',
    missing.length ? missing.map((m) => `索引树列出的文件不存在: ${m}`) : ['索引树条目全部存在']);
}

// R-GOV-05c：docs/ 长期文档（含 ADR）必须登记在 project-index
function checkDocRegistration(files) {
  const idx = fs.readFileSync(path.join(ROOT, 'docs', 'project-index.md'), 'utf8');
  const docs = files.filter(
    (f) => (f.startsWith('docs/') && f.endsWith('.md')) || f.startsWith('docs/adr/'),
  );
  const unregistered = docs.filter((f) => !idx.includes(path.posix.basename(f)));
  record('R-GOV-05c', unregistered.length ? 'fail' : 'pass',
    unregistered.length ? unregistered.map((f) => `未在 project-index 登记: ${f}`) : [`${docs.length} 份长期文档全部登记`]);
}

// R-GOV-04：STATUS.md 锚点 + 阶段看板与 project-status.json 完全一致
// 解析统一走 scripts/lib/status.mjs（sync-status 同源，避免双解析口径漂移）
function checkStatusMirror() {
  const problems = [];
  let json;
  try {
    json = JSON.parse(fs.readFileSync(path.join(ROOT, 'docs', 'project-status.json'), 'utf8'));
  } catch (e) {
    record('R-GOV-04', 'fail', [`project-status.json 解析失败: ${e.message}`]);
    return;
  }
  let snapshot;
  try {
    snapshot = readStatusSnapshot(ROOT);
  } catch (e) {
    record('R-GOV-04', 'fail', [e.message]);
    return;
  }
  const generated = buildStatusJson(snapshot);
  const pairs = [
    ['currentStageId', json.currentStageId, generated.currentStageId],
    ['currentStageName', json.currentStageName, generated.currentStageName],
    ['status', json.status, generated.status],
    ['owner', json.owner, generated.owner],
    ['nextAction', json.nextAction, generated.nextAction],
    ['exitGate', json.exitGate, generated.exitGate],
    ['progressPercent', String(json.progressPercent), String(generated.progressPercent)],
    ['updatedAt', json.updatedAt, generated.updatedAt],
    ['blockers', JSON.stringify(json.blockers ?? []), JSON.stringify(generated.blockers)],
  ];
  for (const [name, a, b] of pairs) {
    if (a !== b) problems.push(`${name}: json=${JSON.stringify(a)} status=${JSON.stringify(b)}`);
  }
  const jsonStages = (json.stages ?? []).map((s) => `${s.id}|${s.name}|${s.status}`);
  const statusStages = generated.stages.map((s) => `${s.id}|${s.name}|${s.status}`);
  if (JSON.stringify(jsonStages) !== JSON.stringify(statusStages)) {
    problems.push(`stages: json=${JSON.stringify(jsonStages)} status=${JSON.stringify(statusStages)}`);
  }
  record('R-GOV-04', problems.length ? 'fail' : 'pass',
    problems.length ? problems : ['状态镜像一致（锚点字段 + blockers + 阶段看板）']);
}

// 卫生检查：大文件、受保护文件、未挂 Issue 号的待办标记（docs/10 R8）
// EXEMPT：本脚本是检测器，源码中的规则词元是功能需要，不属于未完成标记
const MARKER_EXEMPT = new Set(['scripts/check-repo-health.mjs']);
function checkHygiene(files) {
  const problems = [];
  const big = files.filter((f) => fs.statSync(path.join(ROOT, f)).size > 1024 * 1024);
  for (const f of big) problems.push(`文件超过 1MB: ${f}`);

  const protectedFiles = files.filter(
    (f) => f === '.env' || (/^\.env\./.test(f) && f !== '.env.example') || /\.(pem|key)$/.test(f),
  );
  for (const f of protectedFiles) problems.push(`受保护文件被跟踪: ${f}`);

  const gitignore = fs.readFileSync(path.join(ROOT, '.gitignore'), 'utf8');
  if (!/^\.env$/m.test(gitignore)) problems.push('.gitignore 未包含 .env 条目');

  const codeExt = /\.(java|kt|ts|tsx|js|mjs|cjs|vue|sql|ya?ml)$/;
  const markerRe = /\b(TODO|FIXME)\b/;
  for (const f of files.filter((f) => codeExt.test(f) && !MARKER_EXEMPT.has(f))) {
    const lines = fs.readFileSync(path.join(ROOT, f), 'utf8').split('\n');
    lines.forEach((line, i) => {
      if (markerRe.test(line) && !/#\d+/.test(line)) {
        problems.push(`未挂 Issue 号的标记: ${f}:${i + 1}`);
      }
    });
  }
  record('HYGIENE', problems.length ? 'fail' : 'pass',
    problems.length ? problems : ['无 >1MB 文件；无受保护文件被跟踪；.gitignore 覆盖 .env；无未挂 Issue 的标记']);
}

function checkRgov01() {
  try {
    const result = checkLintThresholds(ROOT);
    record('R-GOV-01', result.status, result.detail);
  } catch (e) {
    record('R-GOV-01', 'fail', [`阈值比对执行失败: ${e.message}`]);
  }
}

function printSummary() {
  console.log('== FlexForge check-repo-health（P02 全量版）==');
  for (const r of results) {
    const tag = { pass: 'PASS', fail: 'FAIL', skip: 'SKIP' }[r.status];
    console.log(`[${tag}] ${r.id} ${r.detail[0]}`);
    for (const d of r.detail.slice(1)) console.log(`       ${d}`);
  }
  const pass = results.filter((r) => r.status === 'pass').length;
  const fail = results.filter((r) => r.status === 'fail').length;
  const skip = results.filter((r) => r.status === 'skip').length;
  console.log(`-- 结果: ${pass} pass / ${skip} skip / ${fail} fail --`);
  if (fail > 0) process.exit(1);
}

const files = workspaceFiles();
checkLinks(files);
checkIndexTree(files);
checkDocRegistration(files);
checkStatusMirror();
checkHygiene(files);
checkRgov01();
const frontendOk = runFrontendGates(record);
const backend = runBackendVerify(record);
checkRgov02(backend, record);
checkRgov03(backend, record);
checkRgov06(backend, record);
checkRgov08(backend, record);
if (!frontendOk || (!backend.skipped && !backend.ok)) {
  record('GATE-SUMMARY', 'fail', ['前端或后端门禁存在失败项，见上方明细']);
} else {
  record('GATE-SUMMARY', 'pass', ['前端与后端全部检查通过']);
}
record('R-GOV-07', 'skip', ['契约 fixture 回放：P07/P10']);
record('R-GOV-09', 'skip', ['骨架纯净性：P09']);
printSummary();
