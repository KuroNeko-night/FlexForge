#!/usr/bin/env node
// 论文实验数据导出（docs/12 §3：P11 交付；存档不进仓库，路径记录在进度日志）。
// 用法：node scripts/export-thesis-data.mjs [输出目录=thesis-data-<日期>]
// 依赖：docker compose 已运行的 postgres 服务（凭据取 .env / 环境默认值）。
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const outDir = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.join(ROOT, `thesis-data-${new Date().toISOString().slice(0, 10)}`);
fs.mkdirSync(outDir, { recursive: true });

// 各数据面（docs/12 §2）：AI 任务记录 / 规格版本 / 插件激活失败分布 / 迁移记录
const queries = [
  ['ai-task-log.csv', 'SELECT id, issue_id, kind, model, prompt_version, clarify_rounds,'
    + ' retries, output_valid, error_code, duration_ms, created_at FROM ai_task_log'
    + ' ORDER BY created_at'],
  ['requirement-spec-revisions.csv', 'SELECT rs.issue_id, rs.revision, rs.valid,'
    + ' rs.validation_errors, rs.created_by, rs.created_at FROM requirement_spec rs'
    + ' ORDER BY rs.created_at'],
  ['plugin-activation-outcomes.csv', 'SELECT plugin_id, operation, status, stage, error_code,'
    + ' started_at, finished_at FROM plugin_activation ORDER BY started_at'],
  ['plugin-migrations.csv', 'SELECT pm.plugin_version_id, pm.script_name, pm.checksum,'
    + ' pm.activation_id FROM plugin_migration pm ORDER BY pm.plugin_version_id'],
];

for (const [file, sql] of queries) {
  const csv = execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'db', 'psql', '-U', 'flexforge', '-d', 'flexforge',
      '-c', `\\copy (${sql}) TO STDOUT WITH CSV HEADER`],
    { cwd: ROOT, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 },
  );
  fs.writeFileSync(path.join(outDir, file), csv);
  console.log(`导出 ${file}（${csv.split('\n').length - 2} 行）`);
}
console.log(`完成：${outDir}（存档不进仓库，路径已可记录至进度日志）`);
