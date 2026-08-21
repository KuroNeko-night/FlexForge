#!/usr/bin/env node
// scripts/sync-status：以 STATUS.md 锚点 + 阶段看板为唯一来源，
// 单向生成/校验 docs/project-status.json（R-GOV-04 机器镜像），消除人工双写。
// 用法：node scripts/sync-status.mjs [--check]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { buildStatusJson, readStatusSnapshot } from './lib/status.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const JSON_PATH = path.join(ROOT, 'docs', 'project-status.json');
const checkOnly = process.argv.includes('--check');

function sortObject(value) {
  if (Array.isArray(value)) return value.map(sortObject);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((k) => [k, sortObject(value[k])]));
  }
  return value;
}

const snapshot = readStatusSnapshot(ROOT);
const generated = buildStatusJson(snapshot);

if (checkOnly) {
  let current;
  try {
    current = JSON.parse(fs.readFileSync(JSON_PATH, 'utf8'));
  } catch (e) {
    console.error(`project-status.json 无法解析: ${e.message}`);
    process.exit(1);
  }
  const same = JSON.stringify(sortObject(current)) === JSON.stringify(sortObject(generated));
  if (same) {
    console.log('sync-status: STATUS.md 与 project-status.json 一致');
    process.exit(0);
  }
  console.error('sync-status: 状态镜像漂移，请运行 node scripts/sync-status.mjs 重新生成');
  console.error(`  expected=${JSON.stringify(generated)}`);
  console.error(`  actual=${JSON.stringify(current)}`);
  process.exit(1);
}

fs.writeFileSync(JSON_PATH, `${JSON.stringify(generated, null, 2)}\n`, 'utf8');
console.log(`sync-status: 已从 STATUS.md 生成 ${path.relative(ROOT, JSON_PATH)}`);
console.log(`  stage=${generated.currentStageId} status=${generated.status} progress=${generated.progressPercent}%`);
