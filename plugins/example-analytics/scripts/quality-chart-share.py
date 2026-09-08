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
if not categories:
    # 空数据占位：输出合法 chart 而非空 categories（契约拒绝空数组，空数据≠故障）
    categories = ["暂无数据"]
print(json.dumps({
    "kind": "chart",
    "chartType": "pie",
    "title": "检验结论占比",
    "categories": categories,
    "values": [verdicts.get(category, 0) for category in categories],
}, ensure_ascii=False))
