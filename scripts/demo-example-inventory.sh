#!/usr/bin/env bash
# example-inventory 演示脚本（docs/09 P09）：安装 → 普通用户查询/编辑/非负规则 →
# 停用 → 启用 → 卸载，全程只调用标准插件/数据 API（FR-DEMO-03 同权口径）。
# 依赖：bash、curl、python3；环境：FLEXFORGE_BASE_URL（默认 http://localhost:8080）
set -euo pipefail

BASE_URL="${FLEXFORGE_BASE_URL:-http://localhost:8080}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG_DIR="$REPO_ROOT/plugins/example-inventory"
ZIP_FILE="$(mktemp).zip"

ADMIN_USER="${FLEXFORGE_ADMIN_USER:-admin}"
ADMIN_PASS="${FLEXFORGE_ADMIN_PASS:?需要环境变量 FLEXFORGE_ADMIN_PASS（管理员口令）}"
DEMO_USER="${FLEXFORGE_DEMO_USER:-demo-user}"
DEMO_PASS="${FLEXFORGE_DEMO_PASS:?需要环境变量 FLEXFORGE_DEMO_PASS（演示普通用户口令）}"

json() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)"; }
api() { # api METHOD PATH TOKEN [JSON_BODY] -> 响应体
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
step() { printf '\n== %s ==\n' "$1"; }

# 0. 打包
python3 - "$PKG_DIR" "$ZIP_FILE" << 'PY'
import os, sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w') as z:
    for root, _, files in os.walk(src):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, src))
PY

# 1. 管理员安装（校验 → 导入 → 激活）
step "安装（validate → import → activate）"
admin_token="$(api POST /api/v1/auth/login '' \
  "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" | json "['token']")"
curl -sf -H "Authorization: Bearer $admin_token" -F "file=@$ZIP_FILE" \
  "$BASE_URL/api/v1/plugins/validate" | json "['valid']" | grep -qx True
version_id="$(curl -sf -H "Authorization: Bearer $admin_token" -F "file=@$ZIP_FILE" \
  "$BASE_URL/api/v1/plugins/import" | json "['versionId']")"
activation_id="$(api POST "/api/v1/plugins/$version_id/activate" "$admin_token" | json "['id']")"
echo "versionId=$version_id activationId=$activation_id"

# 2. 管理员与普通用户均见库存菜单
step "菜单出现（管理员与普通用户）"
user_token="$(api POST /api/v1/auth/login '' \
  "{\"username\":\"$DEMO_USER\",\"password\":\"$DEMO_PASS\"}" | json "['token']")"
for t in "$admin_token" "$user_token"; do
  api GET /api/v1/menus "$t" | python3 -c \
    "import sys,json;print(any(m['key']=='example.inventory.items' for m in json.load(sys.stdin)))" \
    | grep -qx True
done

# 3. 普通用户：种子一条业务记录 → 查询 → 非负规则 → 编辑
step "普通用户 CRUD 与非负规则（FR-DEMO-02）"
api POST /api/v1/data/inventory_item "$user_token" \
  '{"sku":"DEMO-BOLT-M6","name":"M6 螺栓","qty":100,"status":"在库"}' >/dev/null
api GET '/api/v1/data/inventory_item?pageSize=10' "$user_token" \
  | json "['items']" | python3 -c "import sys,json;print(len(json.load(sys.stdin))>0)" \
  | grep -qx True
if api POST /api/v1/data/inventory_item "$user_token" \
    '{"sku":"DEMO-BAD","name":"坏数据","qty":-1,"status":"在库"}' >/dev/null 2>&1; then
  echo "非负规则失效！qty=-1 被接受" >&2; exit 1
fi
echo "qty=-1 被拒绝 ✓"

# 4. 停用：菜单消失、数据 API 404（实体 disabled，数据保留）
step "停用（stop）"
api POST "/api/v1/plugins/$activation_id/stop" "$admin_token" >/dev/null
api GET /api/v1/menus "$user_token" | python3 -c \
  "import sys,json;print(any(m['key']=='example.inventory.items' for m in json.load(sys.stdin)))" \
  | grep -qx False
curl -sf -H "Authorization: Bearer $user_token" \
  "$BASE_URL/api/v1/data/inventory_item" >/dev/null 2>&1 \
  && { echo "停用后数据 API 仍可访问！" >&2; exit 1; } || echo "停用后 data API 404 ✓"

# 5. 启用（再次激活）：菜单与数据回归（data_record 保留）
step "启用（re-activate）"
activation_id="$(api POST "/api/v1/plugins/$version_id/activate" "$admin_token" | json "['id']")"
api GET /api/v1/data/inventory_item "$user_token" | python3 -c \
  "import sys,json;print(len(json.load(sys.stdin)['items'])>0)" | grep -qx True

# 6. 卸载：注册撤销、审计保留
step "卸载（uninstall）"
api DELETE /api/v1/plugins/example.inventory "$admin_token" >/dev/null
api GET /api/v1/menus "$user_token" | python3 -c \
  "import sys,json;print(any(m['key']=='example.inventory.items' for m in json.load(sys.stdin)))" \
  | grep -qx False
api GET /api/v1/plugins/inventory "$admin_token" >/dev/null
echo "演示完成：安装 → 规则 → 停用 → 启用 → 卸载 全链路通过"
