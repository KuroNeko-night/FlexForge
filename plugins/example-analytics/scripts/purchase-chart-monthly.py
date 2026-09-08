# 采购月度金额条形图（example.analytics 0.2.0，P22）：按预计到货月份合计采购金额。
# 纯 Python 标准库；stdin {records}，stdout 输出 chart（bar，FR-CHART-01/FR-PLUGIN-13）。
import json
import sys
from collections import defaultdict

data = json.load(sys.stdin)
monthly = defaultdict(float)
for record in data.get("records", []):
    month = (record.get("expected_on") or "")[:7] or "未排期"
    monthly[month] += float(record.get("amount") or 0)

ordered = sorted(m for m in monthly if m != "未排期")
if "未排期" in monthly:
    ordered.append("未排期")
print(json.dumps({
    "kind": "chart",
    "chartType": "bar",
    "title": "采购月度金额合计",
    "categories": ordered,
    "values": [round(monthly.get(month, 0.0), 2) for month in ordered],
}, ensure_ascii=False))
