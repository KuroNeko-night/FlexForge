#!/usr/bin/env bash
# 端到端演示脚本（docs/09 P12、docs/02 §4 场景 A/B/B2/C/D）：五场景单脚本串联并
# 逐环节计时（论文/答辩耗时数据，对照 docs/00 §5 十分钟目标）。
# 依赖：bash、curl、python3；环境：FLEXFORGE_BASE_URL（默认 http://localhost:8080）。
# 前置：
#   1) 干净数据库（只执行平台迁移）——重复演示前先 `docker compose down -v && docker compose up -d`；
#      脚本启动时会检查插件清单为空，非空即中止。
#   2) 管理员账号 FLEXFORGE_ADMIN_USER/PASS（compose 经 FLEXFORGE_BOOTSTRAP_ADMIN_PASSWORD 引导）。
#   3) 演示账号 FLEXFORGE_DEMO_DEV_USER/PASS、FLEXFORGE_DEMO_USER/PASS——不存在时由管理员经
#      系统用户管理 API 创建（DEVELOPER/USER 角色）。
# 耗时输出：stdout `E2E-DEMO,stage,ms` 行；设置 E2E_DEMO_TIMING_FILE 可追加 CSV 留档。
set -euo pipefail

BASE_URL="${FLEXFORGE_BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG_DIR="$REPO_ROOT/plugins/example-inventory"
ZIP_FILE="$(mktemp).zip"
RUN_TAG="$(date +%s)"
ENT_NAME="demo_material_${RUN_TAG}"
TIMING_FILE="${E2E_DEMO_TIMING_FILE:-}"

ADMIN_USER="${FLEXFORGE_ADMIN_USER:-admin}"
ADMIN_PASS="${FLEXFORGE_ADMIN_PASS:?需要环境变量 FLEXFORGE_ADMIN_PASS（管理员口令）}"
DEV_USER="${FLEXFORGE_DEMO_DEV_USER:-demo-developer}"
DEV_PASS="${FLEXFORGE_DEMO_DEV_PASS:?需要环境变量 FLEXFORGE_DEMO_DEV_PASS（演示开发者口令）}"
USER_USER="${FLEXFORGE_DEMO_USER:-demo-user}"
USER_PASS="${FLEXFORGE_DEMO_PASS:?需要环境变量 FLEXFORGE_DEMO_PASS（演示普通用户口令）}"

json() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }
pybool() { python3 -c "$1" | grep -qx True; }
api() { # api METHOD PATH TOKEN [JSON_BODY] -> 响应体（curl -sf，非 2xx 视为失败）
  local method="$1" path="$2" token="${3:-}" body="${4:-}"
  if [ -n "$body" ]; then
    curl -sf -X "$method" -H "Authorization: Bearer $token" \
      -H 'Content-Type: application/json' -d "$body" "$BASE_URL$path"
  elif [ -n "$token" ]; then
    curl -sf -X "$method" -H "Authorization: Bearer $token" "$BASE_URL$path"
  else
    curl -sf -X "$method" "$BASE_URL$path"
  fi
}
now_ms() { # 毫秒时间戳：bash5 内建 EPOCHREALTIME 免子进程开销；老 bash 回退 python3
  if [ -n "${EPOCHREALTIME:-}" ]; then
    echo "$(( ${EPOCHREALTIME%.*} * 1000 + 10#${EPOCHREALTIME#*.} / 1000 ))"
  else
    python3 -c 'import time;print(int(time.time()*1000))'
  fi
}
STAGE_NAME="" STAGE_START=0
stage_begin() { STAGE_NAME="$1"; STAGE_START="$(now_ms)"; printf '\n== 场景 %s ==\n' "$STAGE_NAME"; }
stage_end() {
  local ms=$(( $(now_ms) - STAGE_START ))
  echo "E2E-DEMO,${STAGE_NAME},${ms}"
  if [ -n "$TIMING_FILE" ]; then
    echo "$(date -Iseconds),${STAGE_NAME},${ms}" >> "$TIMING_FILE"
  fi
}

login() {
  local token
  if ! token="$(api POST /api/v1/auth/login '' "{\"username\":\"$1\",\"password\":\"$2\"}" | json "['token']")"; then
    echo "登录失败：$1——检查口令（管理员须与 FLEXFORGE_BOOTSTRAP_ADMIN_PASSWORD 一致；演示账号口令是否被改过）" >&2
    exit 1
  fi
  echo "$token"
}

# 0. 打包演示插件（与仓库内源同步）
python3 - "$PKG_DIR" "$ZIP_FILE" << 'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w') as z:
    for root, _, files in os.walk(src):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, src))
PY

admin_token="$(login "$ADMIN_USER" "$ADMIN_PASS")"

# 预置账号：不存在则创建（幂等——已存在时 4xx 忽略）
ensure_user() {
  api POST /api/v1/system/users "$admin_token" \
    "{\"username\":\"$1\",\"password\":\"$2\",\"displayName\":\"$3\",\"roles\":[\"$4\"]}" >/dev/null 2>&1 || true
}
ensure_user "$DEV_USER" "$DEV_PASS" "演示开发者" "DEVELOPER"
ensure_user "$USER_USER" "$USER_PASS" "演示用户" "USER"
dev_token="$(login "$DEV_USER" "$DEV_PASS")"
user_token="$(login "$USER_USER" "$USER_PASS")"

# ===== 场景 D 前置：干净骨架（非空插件清单即中止，提示重置数据库）=====
stage_begin "D.pre"
plugin_count="$(api GET /api/v1/plugins/inventory "$admin_token" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')"
if [ "$plugin_count" != "0" ]; then
  echo "插件清单非空（$plugin_count 个）——需干净数据库；请 docker compose down -v 后重试" >&2
  exit 1
fi
api GET '/api/v1/meta/entities?page=1&pageSize=10' "$user_token" | pybool "import sys,json;print(json.load(sys.stdin)['total']==0)" \
  || { echo "干净库断言失败：已存在实体" >&2; exit 1; }
stage_end

# ===== 场景 A：开发者建"物料"实体（四字段）→ 启用 → 普通用户 CRUD =====
stage_begin "A"
entity_id="$(api POST /api/v1/meta/entities "$dev_token" \
  "{\"name\":\"$ENT_NAME\",\"displayName\":\"物料\"}" | json "['id']")"
api POST "/api/v1/meta/entities/$entity_id/fields" "$dev_token" \
  '{"name":"name","displayName":"名称","fieldType":"text","required":true,"position":0}' >/dev/null
api POST "/api/v1/meta/entities/$entity_id/fields" "$dev_token" \
  '{"name":"qty","displayName":"数量","fieldType":"integer","validation":{"min":0},"position":1}' >/dev/null
api POST "/api/v1/meta/entities/$entity_id/fields" "$dev_token" \
  '{"name":"unit_price","displayName":"单价","fieldType":"decimal","position":2}' >/dev/null
api POST "/api/v1/meta/entities/$entity_id/fields" "$dev_token" \
  '{"name":"status","displayName":"状态","fieldType":"enum","validation":{"options":["in_stock","sold_out"]},"position":3}' >/dev/null
api PATCH "/api/v1/meta/entities/$entity_id" "$dev_token" '{"status":"enabled"}' >/dev/null
record_id="$(api POST "/api/v1/data/$ENT_NAME" "$user_token" \
  "{\"name\":\"M6 螺栓\",\"qty\":10,\"unit_price\":0.5,\"status\":\"in_stock\"}" | json "['id']")"
api PATCH "/api/v1/data/$ENT_NAME/$record_id" "$user_token" '{"qty":20}' >/dev/null
api GET "/api/v1/data/$ENT_NAME?pageSize=10" "$user_token" | pybool "import sys,json;print(json.load(sys.stdin)['total']==1)" \
  || { echo "场景 A 断言失败" >&2; exit 1; }
stage_end

# ===== 场景 B：example-inventory 安装→使用→停用（含过期激活）→再启用→卸载 =====
stage_begin "B"
version_id="$(curl -sf -H "Authorization: Bearer $admin_token" -F "file=@$ZIP_FILE" \
  "$BASE_URL/api/v1/plugins/import" | json "['versionId']")"
activation_id="$(api POST "/api/v1/plugins/$version_id/activate" "$admin_token" | json "['id']")"
api GET /api/v1/menus "$user_token" | pybool "import sys,json;print(any(m['key']=='example.inventory.items' for m in json.load(sys.stdin)))" \
  || { echo "库存菜单未出现" >&2; exit 1; }
api POST /api/v1/data/inventory_item "$user_token" \
  '{"sku":"DEMO-KEEP-1","name":"保留数据","qty":3,"status":"在库"}' >/dev/null
if api POST /api/v1/data/inventory_item "$user_token" \
  '{"sku":"DEMO-BAD","name":"坏数据","qty":-1,"status":"在库"}' >/dev/null 2>&1; then
  echo "非负规则失效！qty=-1 被接受" >&2; exit 1
fi
api POST "/api/v1/plugins/$activation_id/stop" "$admin_token" >/dev/null
if api GET "/api/v1/plugins/activations/$activation_id/registrations" "$admin_token" >/dev/null 2>&1; then
  echo "过期激活未被拒绝（应 409）" >&2; exit 1
fi
api POST "/api/v1/plugins/$version_id/activate" "$admin_token" >/dev/null
api DELETE /api/v1/plugins/example.inventory "$admin_token" >/dev/null
stage_end

# ===== 场景 B2：依赖未激活 → DEPENDENCY_CHECK 失败、无残留、可清理重试 =====
stage_begin "B2"
dep_zip() { # dep_zip PLUGIN_ID TABLE_SUFFIX DEPS_JSON -> 临时 zip 路径
  python3 - "$1" "$2" "$3" << 'PY'
import io, os, sys, zipfile
plugin_id, suffix, deps = sys.argv[1], sys.argv[2], sys.argv[3]
manifest = ("{\"schemaVersion\":1,\"id\":\"%s\",\"name\":\"%s\",\"version\":\"1.0.0\","
  "\"capabilityLevel\":1,\"minPlatformVersion\":\"0.1.0\",\"dependencies\":%s,"
  "\"contributions\":{\"navigation\":[\"%s.items\"],\"renderers\":[\"enum.default\"]},"
  "\"resources\":{\"entities\":[\"metadata/entities/item.json\"],\"views\":[],"
  "\"migrations\":[\"migrations/V001__init.sql\"]}}") % (plugin_id, plugin_id, deps, plugin_id)
entities = ("{\"name\":\"%s\",\"displayName\":\"依赖项\",\"fields\":[{\"name\":\"sku\","
  "\"displayName\":\"SKU\",\"fieldType\":\"text\",\"required\":true,\"position\":0}]}") % ("dep_" + suffix)
sql = "CREATE TABLE dep_%s (id SERIAL PRIMARY KEY, data JSONB NOT NULL DEFAULT '{}');" % suffix
out = os.environ["TEMP"] if os.name == "nt" else "/tmp"
path = os.path.join(out, "flexforge-%s.zip" % suffix)
with zipfile.ZipFile(path, "w") as z:
    z.writestr("plugin.json", manifest)
    z.writestr("metadata/entities/item.json", entities)
    z.writestr("migrations/V001__init.sql", sql)
print(path)
PY
}
import_plugin() { # import_plugin ZIP -> versionId（只导入不激活）
  curl -sf -H "Authorization: Bearer $admin_token" -F "file=@$1" \
    "$BASE_URL/api/v1/plugins/import" | json "['versionId']"
}
activate_must_fail_with() { # activate_must_fail_with ZIP EXPECT_HTTP EXPECT_CODE
  local vid body http
  vid="$(import_plugin "$1")"
  body="$(curl -s -w '\n%{http_code}' -X POST -H "Authorization: Bearer $admin_token" \
    "$BASE_URL/api/v1/plugins/$vid/activate")"
  http="$(echo "$body" | tail -1)"
  body="$(echo "$body" | head -n -1)"
  if [ "$http" != "$2" ] || [ "$(echo "$body" | json "['code']")" != "$3" ]; then
    echo "激活未按预期失败（期望 HTTP $2/$3，实际 HTTP $http：$body）" >&2
    exit 1
  fi
}
# 依赖插件只导入不激活（base 本身是合法包，激活会成功——不能断言其失败）
import_plugin "$(dep_zip e2e.dep.base base '[]')" >/dev/null
activate_must_fail_with "$(dep_zip e2e.dep child '[{"pluginId":"e2e.dep.base","versionRange":"^1.0.0"}]')" 400 dependency_missing
api DELETE /api/v1/plugins/e2e.dep "$admin_token" >/dev/null
api DELETE /api/v1/plugins/e2e.dep.base "$admin_token" >/dev/null
stage_end

# ===== 场景 C：Issue → AI 澄清（fixture 两轮）→ 批准（此前生成被拒）→ 生成安装 → 测试 → 完成 =====
stage_begin "C"
issue_id="$(api POST /api/v1/issues "$user_token" \
  '{"title":"管理物料库存","description":"库存数量不能为负","labels":["inventory"]}' | json "['id']")"
api POST "/api/v1/issues/$issue_id/clarify" "$user_token" '{}' \
  | pybool "import sys,json;d=json.load(sys.stdin);print(not d['specProduced'] and len(d['questions'])>0)" \
  || { echo "澄清第一轮未提问" >&2; exit 1; }
api POST "/api/v1/issues/$issue_id/clarify" "$user_token" \
  '{"answer":"名称+数量，数量非负，验收为 qty=-1 拒绝"}' | pybool "import sys,json;print(json.load(sys.stdin)['specProduced'])" \
  || { echo "澄清第二轮未产出规格" >&2; exit 1; }
if api POST "/api/v1/issues/$issue_id/generate" "$dev_token" >/dev/null 2>&1; then
  echo "未批准即生成成功（人工审核门失效）" >&2; exit 1
fi
api POST "/api/v1/issues/$issue_id/transition" "$dev_token" '{"to":"APPROVED"}' >/dev/null
gen_body="$(api POST "/api/v1/issues/$issue_id/generate" "$dev_token")"
plugin_id="$(echo "$gen_body" | json "['pluginId']")"
echo "$gen_body" | pybool "import sys,json;d=json.load(sys.stdin);print(d['issue']['status']=='IN_TESTING' and d['version']=='0.1.1')" \
  || { echo "生成后状态/版本异常" >&2; exit 1; }
api POST /api/v1/data/clarify_item "$user_token" '{"name":"生成记录","qty":5}' >/dev/null
if api POST /api/v1/data/clarify_item "$user_token" '{"name":"坏数据","qty":-1}' >/dev/null 2>&1; then
  echo "生成实体非负规则失效" >&2; exit 1
fi
api POST "/api/v1/issues/$issue_id/transition" "$dev_token" '{"to":"TESTED"}' >/dev/null
api POST "/api/v1/issues/$issue_id/transition" "$dev_token" '{"to":"DONE"}' >/dev/null
api DELETE "/api/v1/plugins/$plugin_id" "$admin_token" >/dev/null
echo "Issue=$issue_id Plugin=$plugin_id（AI 全链完成，审计与任务日志见导出脚本）"
stage_end

# ===== 场景 D 后置：无 ACTIVE 激活、无业务菜单残留 =====
stage_begin "D.post"
api GET /api/v1/plugins/inventory "$admin_token" \
  | pybool "import sys,json;print(all(a['status']!='ACTIVE' for p in json.load(sys.stdin) for a in p['activations']))" \
  || { echo "D.post 断言失败：仍有 ACTIVE 激活" >&2; exit 1; }
api GET /api/v1/menus "$user_token" \
  | pybool "import sys,json;print(not any(m['key'].startswith(('example.','gen.')) for m in json.load(sys.stdin)))" \
  || { echo "D.post 断言失败：菜单残留" >&2; exit 1; }
stage_end

echo "端到端演示完成（五场景全部通过）。计时行见上方 E2E-DEMO,* 输出。"
