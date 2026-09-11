# 采购金额分箱直方图（example.histogram，P27）：金额列等宽分箱计数，bar chart。
# 纯 Python 标准库；stdin {records}，stdout 输出 chart（FR-CHART-01/FR-PLUGIN-13 契约）。
import json
import sys

BINS = [(0, 1000, "0-1k"), (1000, 5000, "1k-5k"), (5000, 10000, "5k-10k"),
        (10000, 50000, "10k-50k"), (50000, float("inf"), "50k+")]

data = json.load(sys.stdin)
counts = {label: 0 for _, _, label in BINS}
for record in data.get("records", []):
    try:
        amount = float(record.get("amount") or 0)
    except (TypeError, ValueError):
        continue
    for low, high, label in BINS:
        if low <= amount < high:
            counts[label] += 1
            break

if not any(counts.values()):
    # 空数据占位：合法 chart 而非空 categories（平台契约拒绝空数组）
    print(json.dumps({
        "kind": "chart",
        "chartType": "bar",
        "title": "采购金额分布",
        "categories": ["暂无数据"],
        "values": [0],
    }, ensure_ascii=False))
else:
    print(json.dumps({
        "kind": "chart",
        "chartType": "bar",
        "title": "采购金额分布",
        "categories": [label for _, _, label in BINS],
        "values": [counts[label] for _, _, label in BINS],
    }, ensure_ascii=False))
