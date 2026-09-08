# 检验结论占比饼图（example.analytics 0.2.0，P22）：合格/不合格/待定检验单数量占比。
# 纯 Python 标准库；stdin {records}，stdout 输出 chart（pie，FR-CHART-01/FR-PLUGIN-13）。
import json
import sys
from collections import Counter

data = json.load(sys.stdin)
verdicts = Counter()
for record in data.get("records", []):
    verdict = record.get("verdict") or "待定"
    verdicts[verdict] += 1

categories = sorted(verdicts)
print(json.dumps({
    "kind": "chart",
    "chartType": "pie",
    "title": "检验结论占比",
    "categories": categories,
    "values": [verdicts.get(category, 0) for category in categories],
}, ensure_ascii=False))
