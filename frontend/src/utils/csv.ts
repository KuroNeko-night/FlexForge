/**
 * CSV 导出（P19，P17 候选项裁决纳入）：列表页当前已加载记录的本地导出，
 * 无网络请求、无新端点。转义遵循 RFC 4180（含逗号/引号/换行的字段加引号
 * 包裹、内部引号翻倍；行尾 CRLF）；下载层补 UTF-8 BOM 保 Excel 中文兼容。
 */

/** 单元格口径：null/undefined→空串；boolean→是/否（与 BooleanField display 一致）；其余 String()。 */
function cell(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  return String(value);
}

/** RFC 4180 字段转义：含逗号/引号/换行时双引号包裹，内部引号翻倍。 */
export function csvEscape(value: unknown): string {
  const text = cell(value);
  if (/[",\r\n]/.test(text)) {
    return `"${text.replaceAll('"', '""')}"`;
  }
  return text;
}

/** 组装 CSV 文档（首行表头；CRLF 行尾）。 */
export function buildCsv(headers: string[], rows: unknown[][]): string {
  const lines = [
    headers.map(csvEscape).join(','),
    ...rows.map((row) => row.map(csvEscape).join(',')),
  ];
  return `${lines.join('\r\n')}\r\n`;
}

/** 文件名清洗：替换路径分隔符/Windows 保留字符为连字符（实体显示名来自插件元数据，不可信输入）。 */
export function csvSafeFilename(name: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|]/g, '-').trim();
  return cleaned === '' ? 'export' : cleaned;
}

/** 触发浏览器下载（BOM + text/csv）。revoke 延迟：Safari/部分 WebView 在
 * click 同步返回时尚未读完 blob，立即 revoke 会截断下载（审查 P2-2）。 */
export function downloadCsv(filename: string, content: string): void {
  const blob = new Blob([`\uFEFF${content}`], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 4000);
}
