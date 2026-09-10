# -*- coding: utf-8 -*-
"""CSV 画像处理器（P23，FR-PLUGIN-14 文件输入→table 输出示例）。

输入：argv[1] = 平台分配的上传文件相对名（input.dat）。
输出：stdout 契约 {"kind":"table", columns, rows}——行数（不含表头）、
列数与每列填充率（非空单元格占比，两位百分比）。文件输入不落产物。
"""
import csv
import json
import os
import sys
from pathlib import Path


def contained(root, candidate):
    """realpath 规范化后校验 candidate 位于 root 目录内（拒绝穿越）。"""
    base = os.path.realpath(root)
    target = os.path.realpath(candidate)
    return target == base or target.startswith(base + os.sep)


def main():
    if len(sys.argv) < 2:
        return 2
    input_name = sys.argv[1]
    if not contained(".", input_name):
        return 2
    text = Path(input_name).read_text(encoding="utf-8-sig")
    rows = [row for row in csv.reader(text.splitlines(True))
            if any(cell.strip() for cell in row)]
    if not rows:
        table = [["数据行数", "0"], ["列数", "0"]]
    else:
        header = [name.strip() or "col_%d" % (index + 1)
                  for index, name in enumerate(rows[0])]
        body = rows[1:]
        table = [["数据行数", str(len(body))], ["列数", str(len(header))]]
        for index, name in enumerate(header):
            filled = sum(1 for row in body if index < len(row) and row[index].strip())
            rate = "%.2f%%" % (100.0 * filled / len(body)) if body else "0.00%"
            table.append([name, rate])
    print(json.dumps({"kind": "table",
                      "columns": [{"name": "metric", "label": "指标"},
                                  {"name": "value", "label": "值"}],
                      "rows": table},
                     ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
