# 检验合格率统计（example.analytics，P20）：按供应商分组聚合——检验单数/合格率/不合格数量。
# 纯 Python 标准库；stdin {records}，stdout 输出 table。
import json
import sys
from collections import defaultdict

data = json.load(sys.stdin)
stats = defaultdict(lambda: {"total": 0, "pass": 0, "defect": 0})
for record in data.get("records", []):
    supplier = record.get("supplier") or "未指定"
    bucket = stats[supplier]
    bucket["total"] += 1
    if record.get("verdict") == "合格":
        bucket["pass"] += 1
    bucket["defect"] += int(record.get("defect_qty") or 0)

columns = [
    {"name": "supplier", "label": "供应商"},
    {"name": "inspections", "label": "检验单数"},
    {"name": "pass_rate", "label": "合格率"},
    {"name": "defect_qty", "label": "不合格数量"},
]
rows = []
for supplier in sorted(stats):
    bucket = stats[supplier]
    rate = round(bucket["pass"] * 100.0 / bucket["total"], 1) if bucket["total"] else 0.0
    rows.append([supplier, bucket["total"], f"{rate}%", bucket["defect"]])
print(json.dumps({"kind": "table", "columns": columns, "rows": rows}, ensure_ascii=False))
