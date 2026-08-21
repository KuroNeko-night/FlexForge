// check-repo-health 的门禁执行层：命令封装、前端工具链、后端 verify、
// R-GOV-02（ArchUnit 依赖边界）与 R-GOV-06（门禁防失效）的真实执行。
import { execFileSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
export const BACKEND = path.join(ROOT, 'backend');
export const FRONTEND = path.join(ROOT, 'frontend');

export function runCapture(cmd, args, cwd) {
  // Windows 宿主/沙箱差异：Node 直接 spawn .cmd 可能报 EINVAL，统一经 ComSpec 执行；
  // Linux 保持直接执行（backend/mvnw 的可执行位已提交）。
  const isWindowsBatch = process.platform === 'win32' && /\.(cmd|bat)$/i.test(cmd);
  const command = isWindowsBatch ? process.env.ComSpec || 'cmd.exe' : cmd;
  const commandArgs = isWindowsBatch
    ? ['/d', '/s', '/c', [cmd, ...args].map((a) => (/\s/.test(a) ? `"${a}"` : a)).join(' ')]
    : args;
  try {
    const stdout = execFileSync(command, commandArgs, {
      cwd,
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
      windowsHide: true,
    });
    return { ok: true, stdout, code: 0 };
  } catch (e) {
    return {
      ok: false,
      stdout: String(e.stdout ?? ''),
      stderr: String(e.stderr ?? '') || e.message,
      code: typeof e.status === 'number' ? e.status : 1,
    };
  }
}

const npmCmd = () => (process.platform === 'win32' ? 'npm.cmd' : 'npm');
const mvnCmd = () => (process.platform === 'win32' ? 'mvnw.cmd' : './mvnw');
const tailLines = (text, count = 6) => text.trim().split('\n').slice(-count);

export function ensureFrontendDeps(record) {
  if (fs.existsSync(path.join(FRONTEND, 'node_modules', '.bin'))) {
    return true;
  }
  const r = runCapture(npmCmd(), ['ci', '--no-fund', '--no-audit'], FRONTEND);
  record('FRONTEND-DEPS', r.ok ? 'pass' : 'fail',
    r.ok ? ['npm ci 完成'] : tailLines(`${r.stdout}\n${r.stderr}`.trim()));
  return r.ok;
}

export function runFrontendGates(record) {
  if (!fs.existsSync(path.join(FRONTEND, 'package.json'))) {
    record('FRONTEND', 'skip', ['frontend/package.json 不存在（迭代 2 就位后启用）']);
    return false;
  }
  if (!ensureFrontendDeps(record)) {
    return false;
  }
  let ok = true;
  for (const [id, script] of [
    ['FORMAT', 'format:check'],
    ['LINT', 'lint'],
    ['TYPE-CHECK', 'type-check'],
    ['TEST', 'test'],
    ['BUILD', 'build'],
  ]) {
    const r = runCapture(npmCmd(), ['run', script], FRONTEND);
    record(id, r.ok ? 'pass' : 'fail',
      r.ok ? [`npm run ${script} 通过`] : tailLines(`${r.stdout}\n${r.stderr}`.trim()));
    ok &&= r.ok;
  }
  // 依赖漏洞扫描（docs/13 §3.9：npm audit 并入 check-repo-health 报告项）
  const audit = runCapture(npmCmd(), ['audit', '--omit=dev', '--audit-level=high'], FRONTEND);
  record('AUDIT', audit.ok ? 'pass' : 'fail',
    audit.ok ? ['npm audit（high+）无命中'] : tailLines(`${audit.stdout}\n${audit.stderr}`.trim()));
  ok &&= audit.ok;
  return ok;
}

export function runBackendVerify(record) {
  if (!fs.existsSync(path.join(BACKEND, 'pom.xml'))) {
    record('BACKEND-VERIFY', 'skip', ['backend/pom.xml 不存在（迭代 1 就位后启用）']);
    return { ok: true, skipped: true };
  }
  const r = runCapture(mvnCmd(), ['-B', '-ntp', 'verify'], BACKEND);
  record('BACKEND-VERIFY', r.ok ? 'pass' : 'fail',
    r.ok ? ['Maven verify 通过（Checkstyle + ArchUnit + Testcontainers 冒烟）']
      : tailLines(`${r.stdout}\n${r.stderr}`.trim()));
  return { ok: r.ok, skipped: false };
}

function surefireReport(name) {
  const report = path.join(
    BACKEND,
    'flexforge-app',
    'target',
    'surefire-reports',
    `TEST-com.flexforge.app.${name}.xml`,
  );
  if (!fs.existsSync(report)) {
    return null;
  }
  const xml = fs.readFileSync(report, 'utf8');
  const suite = xml.match(/<testsuite[^>]*tests="(\d+)"[^>]*errors="(\d+)"[^>]*failures="(\d+)"/);
  if (!suite) {
    return { tests: 0, errors: 0, failures: 0 };
  }
  return {
    tests: Number.parseInt(suite[1], 10),
    errors: Number.parseInt(suite[2], 10),
    failures: Number.parseInt(suite[3], 10),
  };
}

export function checkRgov02(backend, record) {
  if (backend.skipped) {
    record('R-GOV-02', 'skip', ['后端未初始化']);
    return;
  }
  const report = surefireReport('DependencyBoundaryTest');
  const ok = backend.ok && report !== null && report.tests === 4 && report.errors === 0 && report.failures === 0;
  record('R-GOV-02', ok ? 'pass' : 'fail',
    ok ? ['ArchUnit 依赖边界 4 条规则通过（common→app、framework 依赖、infrastructure、循环）']
      : ['依赖边界测试失败或报告缺失，禁止带病合并']);
}

export function fixtureManifestProblems() {
  const manifestPath = path.join(ROOT, 'tests', 'fixtures', 'fixtures.json');
  const problems = [];
  let manifest;
  try {
    manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  } catch (e) {
    return [`fixtures.json 解析失败: ${e.message}`];
  }
  for (const entry of manifest.entries ?? []) {
    const file = path.join(ROOT, 'tests', 'fixtures', entry.path);
    if (!fs.existsSync(file)) {
      problems.push(`fixture 缺失: ${entry.path}`);
      continue;
    }
    const hash = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
    if (hash !== entry.sha256) {
      problems.push(`fixture 哈希漂移: ${entry.path} 期望 ${entry.sha256} 实际 ${hash}`);
    }
  }
  return problems;
}

export function checkFrontendViolationFixture() {
  // 必须从仓库根运行：ESLint flat config 的 files 基路径随 cwd 决定，
  // 从 frontend/ 运行会把根目录外的 fixture 判为 outside of base path（fail-open）。
  const fixture = path.relative(ROOT, path.join(ROOT, 'tests', 'fixtures', 'violations', 'frontend-over-limit.ts'));
  const eslintConfig = 'frontend/eslint.config.js';
  const bin = path.join(FRONTEND, 'node_modules', '.bin',
    process.platform === 'win32' ? 'eslint.cmd' : 'eslint');
  const r = runCapture(bin, [fixture, '--config', eslintConfig, '--format', 'json'], ROOT);
  if (r.ok) {
    return { ok: false, detail: ['门禁已失效：超限 + 高复杂度样例通过了 ESLint'] };
  }
  const payload = r.stdout.trim();
  if (!payload) {
    return { ok: false, detail: ['门禁已失效：ESLint 失败但无 JSON 输出，无法确认命中规则'] };
  }
  try {
    const messages = JSON.parse(payload).flatMap((file) => file.messages);
    const rules = new Set(messages.map((m) => m.ruleId).filter(Boolean));
    const missing = ['max-lines', 'complexity'].filter((rule) => !rules.has(rule));
    if (missing.length > 0) {
      return { ok: false, detail: [`门禁已失效：违规样例未命中预期规则 ${missing.join(', ')}`] };
    }
    return { ok: true, detail: ['违规样例被 ESLint 拦截，命中 max-lines + complexity（门禁有效）'] };
  } catch {
    return { ok: false, detail: ['门禁已失效：ESLint JSON 输出无法解析'] };
  }
}

export function checkRgov06(backend, record) {
  if (backend.skipped) {
    record('R-GOV-06', 'skip', ['后端未初始化']);
    return;
  }
  const manifest = fixtureManifestProblems();
  const frontend = checkFrontendViolationFixture();
  const report = surefireReport('CheckstyleFixtureTest');
  const backendOk = backend.ok && report !== null && report.tests === 1 && report.errors === 0 && report.failures === 0;
  const ok = manifest.length === 0 && frontend.ok && backendOk;
  const detail = [];
  if (manifest.length === 0) detail.push('fixtures.json SHA-256 全部一致');
  else detail.push(...manifest);
  detail.push(...frontend.detail);
  detail.push(backendOk
    ? 'Checkstyle 违规样例被拦截，命中 FileLength + MethodLength（门禁有效）'
    : 'Checkstyle 防失效测试失败或报告缺失');
  record('R-GOV-06', ok ? 'pass' : 'fail', detail);
}
