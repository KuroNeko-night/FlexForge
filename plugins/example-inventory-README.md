# example-inventory 库存示例插件

FlexForge 的 Level 1 声明式演示插件（FR-DEMO-01/02/03）：以**普通插件包**交付，
预置但不自动安装，与第三方插件完全同权——只能通过标准插件管理 API
导入/激活/停用/启用/卸载，无任何骨架硬编码（NFR-SKEL-01）。

## 内容

- **实体** `inventory_item`（库存项）：`sku` 物料编码（text，必填，≤64）、
  `name` 物料名称（text，必填）、`qty` 库存数量（integer，必填，**min:0 非负规则**，
  FR-DEMO-02）、`status` 状态（enum：在库/缺货/预定）。
- **视图**：list `库存列表`、form `库存表单`（columns 同实体字段序）。
- **导航**：`example.inventory.items`（激活后菜单可见，路由指向
  `/data/inventory_item` 动态实体页）。
- **迁移**：`V001` 建插件自有台账表 `example_inventory_item`（含 UNIQUE(sku)
  与 CHECK (qty>=0) 数据库层兜底）；`V002` 种子数据（可重复脚本，
  `ON CONFLICT DO NOTHING`）。

## 安装（只调用标准 API）

```bash
# 1. 打包（在仓库根目录）
python3 -c "import zipfile,os; z=zipfile.ZipFile('example-inventory.zip','w'); [z.write(os.path.join(r,f),os.path.relpath(os.path.join(r,f),'plugins/example-inventory')) for r,_,fs in os.walk('plugins/example-inventory') for f in fs]; z.close()"

# 2. 管理员登录换取令牌后：校验 → 导入 → 激活
curl -s -H "Authorization: Bearer $TOKEN" -F file=@example-inventory.zip \
  http://localhost:8080/api/v1/plugins/validate
curl -s -H "Authorization: Bearer $TOKEN" -F file=@example-inventory.zip \
  http://localhost:8080/api/v1/plugins/import        # 记录 $.versionId
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/plugins/$VERSION_ID/activate
```

完整生命周期演示（安装 → 普通用户查询/编辑/规则验证 → 停用 → 启用 → 卸载）
见 `scripts/demo-example-inventory.sh`；UI 可见的业务记录种子（经 data API
写入 data_record）也由该脚本演示。

## 停用 / 启用 / 卸载

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/plugins/$ACTIVATION_ID/stop   # 停用：菜单消失、实体 disabled、数据保留
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/plugins/$VERSION_ID/activate  # 再次激活即启用
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/plugins/example.inventory      # 卸载：注册全撤销、审计保留
```
