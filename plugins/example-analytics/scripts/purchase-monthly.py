# 采购月度透视（example.analytics，P20）：行=供应商，列=预计到货月份，值=金额合计。
# 纯 Python 标准库；stdin {records}（平台组装，docs/09 P20 契约），stdout 输出 table。
import json
import sys
from collections import defaultdict

data = json.load(sys.stdin)
pivots = defaultdict(lambda: defaultdict(float))
months = set()
for record in data.get("records", []):
    supplier = record.get("supplier") or "未指定"
    month = (record.get("expected_on") or "")[:7]
    if not month:
        month = "未排期"
    months.add(month)
    pivots[supplier][month] += float(record.get("amount") or 0)

ordered = sorted(m for m in months if m != "未排期")
if "未排期" in months:
    ordered.append("未排期")
columns = [{"name": "supplier", "label": "供应商"}] + [
    {"name": month, "label": month} for month in ordered
]
rows = [
    [supplier] + [round(pivots[supplier].get(month, 0.0), 2) for month in ordered]
    for supplier in sorted(pivots)
]
print(json.dumps({"kind": "table", "columns": columns, "rows": rows}, ensure_ascii=False))
