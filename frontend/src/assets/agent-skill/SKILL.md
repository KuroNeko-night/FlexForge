---
name: flexforge-plugin-dev
description: 教 AI 助手为 FlexForge 平台制作声明式插件：插件包结构、plugin.json 清单、实体/视图 JSON、迁移 SQL、Python 数据处理器（实体输入/文件输入两种模式）、打包校验红线与导入激活全流程。收到"FlexForge 插件制作提示词"或要求为 FlexForge 开发插件/数据模块/表格工具时使用本技能。
---

# FlexForge 插件开发技能

## 0. FlexForge 是什么

FlexForge 是一个**模块化数据管理系统**（Vue 3 + Spring Boot 3 + PostgreSQL）。它的核心思路：
业务功能不写代码，而是以**声明式插件包**（一个 zip）交付——插件声明"实体（表单字段）、
视图（列表/看板/表单）、菜单、数据处理器"，平台负责校验、导入、激活、渲染与权限。
你（AI）的任务：根据制作提示词，产出一个**可通过平台校验器**的插件 zip 包（或等价目录）。

两条硬边界：

1. **只做声明式能力**：实体 CRUD、列表/看板/表单视图、Python 数据处理器、主题资产。
   平台没有的能力（任意后端代码、定时任务、外部网络调用、自定义前后端接口）不要发明。
2. **内容不可变**：同一个插件 `id + version` 导入后内容永久锁定；改任何文件都必须升版本号
   （如 0.1.0 → 0.1.1），否则重新导入会被拒绝。

## 1. 你要产出什么

一个 zip 包，根目录必须含 `plugin.json`，整体布局：

```
plugin.json                        ← 清单（必需，唯一入口）
metadata/entities/<entity>.json    ← 实体定义（字段/类型/校验）
metadata/views/<entity>.<type>.json ← 视图定义（list/form/kanban）
migrations/V001__<name>.sql        ← 数据库迁移（可选，按序号执行一次）
scripts/<name>.py                  ← Python 处理器脚本（Level 2，可选）
assets/<file>                      ← 主题资产（可选，需 manifest 声明）
```

区域-扩展名白名单（违反即拒）：`metadata/` 只收 `.json`；`migrations/` 只收 `.sql`；
`scripts/` 只收 `.py`；`assets/` 只收 `.png/.svg/.webp/.css/.json`。
包上限：压缩后 ≤10MB、解压后 ≤50MB、条目 ≤1000；禁止绝对路径、`..`、反斜杠、控制字符。

## 2. plugin.json 清单（逐字段）

完整示例（实体插件 + 看板，最常见形态）：

```json
{
  "schemaVersion": 1,
  "id": "demo.meeting",
  "name": "会议室预约",
  "version": "0.1.0",
  "capabilityLevel": 1,
  "minPlatformVersion": "0.1.0",
  "dependencies": [],
  "permissions": ["meeting.read", "meeting.write"],
  "contributions": {
    "navigation": ["demo.meeting.room"],
    "renderers": ["text.default", "integer.default", "enum.default"]
  },
  "resources": {
    "entities": ["metadata/entities/meeting_room.json"],
    "views": [
      "metadata/views/meeting_room.list.json",
      "metadata/views/meeting_room.form.json"
    ],
    "migrations": ["migrations/V001__meeting_room.sql"]
  }
}
```

字段口径（与校验器一致，出错就是 400 invalid_manifest）：

- `id`：`小写字母开头的小写字母/数字/下划线` + `.` 分段，至少两段（如 `example.purchase`）。
  全局唯一，建议 `作者.业务` 形态。
- `version`：严格 `X.Y.Z` 三段数字。
- `capabilityLevel`：`1`（纯声明）或 `2`（含 Python 处理器）。不含处理器必须填 1。
- `dependencies`：依赖的其他插件，项为 `{"id": "...", "versionRange": "^1.0.0"}`；
  `^0.x` 锁次版本、`^1.x` 锁主版本、`*` 任意。被依赖插件必须**已激活**（仅导入不够）。
- `contributions.navigation`：导航键数组，键全局唯一，建议 `<插件id>.<实体名>`；
  菜单点击会进入**第一个实体**的数据页。
- `contributions.renderers`：只能引用平台内置渲染器 `<类型>.default`（六类：
  text/integer/decimal/date/enum/boolean），不要发明自定义渲染器 ID。
- `resources` 三数组与包内文件**双向核对**：声明了但文件不存在 → 拒；
  包里有 metadata/migrations 文件但未声明 → 拒。

## 3. 实体定义 metadata/entities/<name>.json

```json
{
  "name": "meeting_room",
  "displayName": "会议室预约",
  "fields": [
    { "name": "title", "displayName": "会议主题", "fieldType": "text",
      "required": true, "validation": { "maxLength": 80 }, "position": 0 },
    { "name": "attendees", "displayName": "人数", "fieldType": "integer",
      "required": true, "validation": { "min": 1, "max": 500 }, "position": 1 },
    { "name": "room", "displayName": "会议室", "fieldType": "enum",
      "required": true, "validation": { "options": ["第一会议室", "第二会议室", "多功能厅"] }, "position": 2 },
    { "name": "booked_on", "displayName": "日期", "fieldType": "date",
      "required": false, "position": 3 },
    { "name": "projector", "displayName": "需要投影", "fieldType": "boolean",
      "required": false, "position": 4 },
    { "name": "fee", "displayName": "费用", "fieldType": "decimal",
      "required": false, "validation": { "min": 0 }, "position": 5 }
  ]
}
```

- 实体名/字段名：`^[a-z][a-z0-9_]*$`，≤63 字符；`name` 全局唯一（含平台与其他插件）。
- `fieldType` 六选一：`text/integer/decimal/date/enum/boolean`。
- `validation` 按类型白名单：text→`maxLength`/`minLength`；integer/decimal→`min`/`max`
  （decimal 精度 NUMERIC(20,6)：整数位 ≤14、小数位 ≤6）；enum→`options`（必填非空）；
  date/boolean 无校验键。
- `position` 决定表单/列表列序（0 起）。
- 业务数据不建业务表：记录存于平台动态存储；`migrations` 只放插件**自有**对象
  （如状态台账、种子数据）。

## 4. 视图定义 metadata/views/<entity>.<viewType>.json

三种视图类型，每实体每类型至多一份：

```json
{ "entity": "meeting_room", "viewType": "list", "name": "预约列表",
  "columns": [{ "field": "title" }, { "field": "room" }, { "field": "booked_on" }] }

{ "entity": "meeting_room", "viewType": "form", "name": "预约表单",
  "columns": [{ "field": "title" }, { "field": "attendees" }, { "field": "room" }] }

{ "entity": "meeting_room", "viewType": "kanban", "name": "预约看板",
  "groupBy": "room", "columns": [{ "field": "title" }] }
```

- `viewType` ∈ `list/form/kanban`；`columns.field` 必须是同实体的已声明字段。
- 看板必填 `groupBy`，且该字段必须是 **enum** 字段（列 = 枚举选项）。
- 表单/列表/看板入口与"新增记录/编辑"由平台自动生成，无需写任何页面代码。

## 5. 迁移 migrations/V00X__<name>.sql

- 命名严格 `^migrations/V\d+__名字.sql$`，按 manifest 声明顺序执行，**每脚本只执行一次**
  （校验和记录，升级复用跳过；内容不同则拒）。
- 只允许操作**插件自有表**：表名以插件短名前缀（如 `example_purchase_order`）；
  禁止出现 `sys_`/`meta_`/`data_`/`plugin_` 平台前缀的任何对象——导入期静态扫描直接拒。
- 脚本内**不要用反斜杠**（`\` 整体拒绝，含 `E'...'` 转义）；用标准 SQL。
- 种子数据用幂等形式：`INSERT ... ON CONFLICT DO NOTHING`。
  注意：升级场景避免 `ON CONFLICT (显式列)` 与旧版已建唯一键失配——无目标形式最稳。
- 实体业务数据不需要建表（见 §3 末条）。

## 6. 数据处理器（可选，capabilityLevel: 2）

声明在 `contributions.processors`，脚本放 `scripts/*.py`（文件名 `[a-z0-9_-]+.py`）。

### 6.1 实体输入模式（默认）：分析该实体的记录

```json
{ "key": "demo.meeting.room_stats", "label": "各会议室预约数", "kind": "python",
  "entry": "scripts/room_stats.py", "inputEntity": "meeting_room" }
```

- `inputEntity` 必填且必须是同包声明的实体；不接受文件字段。
- 运行契约：平台把该实体记录（JSON 数组，单页 ≤200 行、≤2MB）写入**stdin**；
  脚本用 `python3 -I` 隔离模式执行、环境变量清空、10 秒超时、串行执行；
  stdout 必须是**单个 JSON 对象**（≤1MB），进程退出码必须为 0。

### 6.2 文件输入模式：上传表格文件 → 处理 → 输出

```json
{ "key": "demo.csv.clean", "label": "CSV 清洗", "kind": "python",
  "entry": "scripts/csv_clean.py", "inputMode": "file",
  "accept": ["csv", "txt"], "maxInputMB": 5 }
```

- `inputMode: "file"`；`accept` ⊆ `csv/xlsx/txt`；`maxInputMB` 1..5；
  不能与 `inputEntity` 同时出现。
- 上传侧平台已做三重校验（扩展名 ∈ accept、魔数嗅探、大小 ≤ min(maxInputMB, 5MB)）。
- 运行契约：输入文件固定为当前目录下 `input.dat`（即 argv[1] 传给它，但文件名恒定）；
  输出目录经环境变量 `FLEXFORGE_OUTPUT_DIR` 传入；其余同上（隔离/超时/串行/stdout）。

### 6.3 输出契约（stdout 单个 JSON 对象，四选一）

```json
{"kind": "table", "title": "各会议室预约数", "columns": [{"key": "room", "label": "会议室"}, {"key": "count", "label": "次数"}], "rows": [{"room": "第一会议室", "count": 3}]}
{"kind": "summary", "title": "汇总", "items": [{"label": "总预约数", "value": "12"}]}
{"kind": "chart", "chartType": "bar", "title": "月度金额", "categories": ["2026-08", "2026-09"], "values": [1200.5, 860.0]}
{"kind": "file", "filename": "cleaned.csv"}
```

- `file` 形态：把产物文件写入 `FLEXFORGE_OUTPUT_DIR`（仅文件输入模式可用）；
  `filename` 匹配 `^[A-Za-z0-9][A-Za-z0-9._-]*$` 且 ≤200 字符、产物 ≤10MB、
  必须落在输出目录内。产物是临时文件（10 分钟 TTL），用户经平台下载。
- `chart`：`chartType` ∈ `bar/pie`；`categories`/`values` 等长、≤50 项；pie 值 ≥0。
- 处理器 `key`：`^[a-z][a-z0-9_]*(\.[a-z0-9_]*)*$`（点分小写，**段内不要连字符**，
  `my-tool` 会被拒，用 `my_tool`）；同包内 key 不得重复。
- 脚本建议只用 Python 标准库（csv/json/statistics/pathlib），平台运行环境不保证第三方包。
- 范例参考：仓库 `plugins/example-analytics`（实体输入+图表）、
  `plugins/example-filetools`（文件输入+文件产物）。

## 7. 主题资产（可选，进阶）

`contributions.themeAssets` 声明 `{"key","kind","path","scope"}`，kind ∈
`background/icon/animation/tokens/locale`；文件放 `assets/`（路径
`^assets/[a-z0-9_-]+(/...)*\.(png|svg|webp|css|json)$`，段内禁点）。
`tokens`/`locale` 是 JSON 文档（设计令牌键值 / 语言包）。本技能默认不涉及；
需要时参考仓库 `plugins/theme-default` 与 `plugins/locale-en`。

## 8. 打包与校验红线（自查清单）

打包：zip 根目录直接是 `plugin.json`（不要外包一层目录）；UTF-8；条目名用 `/`。
上传前逐条自查：

- [ ] id 两段以上点分小写；version 三段数字；本版内容与历史版本不同则已升版本号
- [ ] capabilityLevel 与内容一致（有 processors 才是 2）
- [ ] renderers 全部是六个 `<type>.default` 之一
- [ ] resources 声明与包内文件一一对应（双向）
- [ ] 实体/字段名 snake_case ≤63；validation 键在类型白名单内；enum 有 options
- [ ] 看板有 groupBy 且指向 enum 字段；视图 columns 字段都存在
- [ ] 迁移名 `V\d+__x.sql` 递增；无平台前缀对象；无反斜杠；种子幂等
- [ ] 处理器 key 无连字符；file 模式无 inputEntity；accept/maxInputMB 在界内
- [ ] 无 `.DS_Store`/临时文件/绝对路径；≤10MB/1000 条目

## 9. 导入与激活（交付给使用者的步骤）

**界面路径（推荐）**：管理员登录 → 左侧"插件管理" → 拖入 zip（选包即自动校验，
findings 全过才可导入）→ 导入 → 打开插件开关（=激活当前版本；多版本可展开切换）。
激活成功后：菜单出现插件入口，进入即列表/看板/新增记录；处理器出现在对应实体的
"数据分析"抽屉或"文件工具"页。

**API 路径（脚本化）**：

```
POST /api/v1/plugins/validate    multipart file=<zip>     # 预检，不落库
POST /api/v1/plugins/import      multipart file=<zip>     # 导入（幂等：同内容重导返回既有版本）
POST /api/v1/plugins/versions/{versionId}/activate        # 激活
GET  /api/v1/plugins/inventory                            # 清单（版本/激活/失败诊断）
```

（认证 Bearer JWT，管理员角色；激活失败时 inventory 会给出失败阶段与错误码。）

## 10. 从制作提示词到插件包（工作法）

1. 读提示词：提取实体清单（名称/类型/必填/校验）、视图形态（列表/看板分组）、
   规则、验收标准。提示词只引用上述声明式能力——出现平台没有的东西，先与需求方确认裁剪。
2. 起草目录：plugin.json + entities + views（+migrations 如需台账/种子；+processors 如需分析）。
3. 按 §8 清单自查 → 打 zip → （可行则）走 §9 validate 预检 → 交付 zip + 导入步骤说明。
4. 汇报格式：插件 id/版本、实体与字段摘要、视图形态、（如有）处理器 key 与输出形态、
   迁移内容摘要、以及"升版说明"（相对上一版改了什么、为什么升版）。

常见失败速查：`invalid_manifest`=清单/资源问题；`dependency_missing`=依赖未**激活**；
`migration_failed`=SQL 执行失败（看阶段码）；`processor_output_invalid`=stdout 契约违约；
同版本重导被拒=忘了升版本号。
