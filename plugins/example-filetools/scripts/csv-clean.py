# -*- coding: utf-8 -*-
"""CSV 清洗处理器（P23，FR-PLUGIN-14 文件输入示例）。

输入：argv[1] = 平台分配的上传文件相对名（input.dat，位于进程工作目录）。
输出：stdout 契约 {"kind":"file","filename":"cleaned.csv"}；清洗后的 CSV
写入 FLEXFORGE_OUTPUT_DIR/cleaned.csv（去空行、整行去重保留首次、列名
规范化去首尾空白、空列名补 col_N）。路径守卫：realpath 规范化后强制
落在允许根（工作目录/输出目录）之内（平台侧已约束边界，此处纵深防御）。
"""
import csv
import io
import json
import os
import sys
from pathlib import Path

OUTPUT_NAME = "cleaned.csv"


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
    output_root = os.environ.get("FLEXFORGE_OUTPUT_DIR", ".")
    output_path = os.path.join(output_root, OUTPUT_NAME)
    if not contained(output_root, output_path):
        return 2
    text = Path(input_name).read_text(encoding="utf-8-sig")
    rows = [row for row in csv.reader(text.splitlines(True))
            if any(cell.strip() for cell in row)]
    if not rows:
        rows = [["col_1"]]
    header = [name.strip() or "col_%d" % (index + 1) for index, name in enumerate(rows[0])]
    seen = set()
    deduped = []
    for row in rows[1:]:
        key = tuple(row)
        if key not in seen:
            seen.add(key)
            deduped.append(row)
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(header)
    writer.writerows(deduped)
    # write_bytes：write_text 在 Windows 做换行翻译，会叠加 csv 自带 \r\n 产生 \r\r\n
    Path(output_path).write_bytes(buffer.getvalue().encode("utf-8"))
    print(json.dumps({"kind": "file", "filename": OUTPUT_NAME}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
