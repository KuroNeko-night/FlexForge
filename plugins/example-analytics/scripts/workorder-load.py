# 工单负载汇总（example.analytics，P20）：按状态计数/计划量合计 + 最高负载班组。
# 纯 Python 标准库；stdin {records}，stdout 输出 summary。
import json
import sys
from collections import Counter, defaultdict

data = json.load(sys.stdin)
by_status = Counter()
qty_by_status = defaultdict(int)
team_load = Counter()
for record in data.get("records", []):
    status = record.get("status") or "未设置"
    by_status[status] += 1
    qty_by_status[status] += int(record.get("plan_qty") or 0)
    team = record.get("team")
    if team:
        team_load[team] += int(record.get("plan_qty") or 0)

top_team = team_load.most_common(1)
items = [{"label": status, "value": f"{count} 单 / {qty_by_status[status]} 件"}
         for status, count in by_status.most_common()]
items.append({"label": "计划数量合计", "value": sum(qty_by_status.values())})
items.append({
    "label": "最高负载班组",
    "value": f"{top_team[0][0]}（{top_team[0][1]} 件）" if top_team else "—",
})
print(json.dumps({"kind": "summary", "items": items}, ensure_ascii=False))
