# 工单交叉矩阵（example.crosstab，P27）：行=产品，列=工单状态，值=计数。
# 纯 Python 标准库；stdin {records}（平台组装，docs/09 P20 契约），stdout 输出 table。
import json
import sys
from collections import defaultdict

data = json.load(sys.stdin)
cells = defaultdict(lambda: defaultdict(int))
statuses = set()
for record in data.get("records", []):
    product = record.get("product") or "未指定"
    status = record.get("status") or "未设置"
    statuses.add(status)
    cells[product][status] += 1

ordered = sorted(statuses)
columns = [{"name": "product", "label": "产品"}] + [
    {"name": status, "label": status} for status in ordered
]
rows = [
    [product] + [cells[product].get(status, 0) for status in ordered]
    for product in sorted(cells)
]
print(json.dumps({"kind": "table", "columns": columns, "rows": rows}, ensure_ascii=False))
