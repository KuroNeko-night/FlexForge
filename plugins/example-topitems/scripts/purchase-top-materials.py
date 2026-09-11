# 热门物料榜（example.topitems，P27）：物料出现频次 Top10，bar chart。
# 纯 Python 标准库；stdin {records}，stdout 输出 chart（FR-CHART-01/FR-PLUGIN-13 契约）。
import json
import sys
from collections import Counter

data = json.load(sys.stdin)
counter = Counter()
for record in data.get("records", []):
    material = (record.get("material") or "").strip()
    if material:
        counter[material] += 1

top = counter.most_common(10)
if not top:
    # 空数据占位：合法 chart 而非空 categories（平台契约拒绝空数组）
    print(json.dumps({
        "kind": "chart",
        "chartType": "bar",
        "title": "物料采购频次 Top10",
        "categories": ["暂无数据"],
        "values": [0],
    }, ensure_ascii=False))
else:
    print(json.dumps({
        "kind": "chart",
        "chartType": "bar",
        "title": "物料采购频次 Top10",
        "categories": [name for name, _ in top],
        "values": [count for _, count in top],
    }, ensure_ascii=False))
