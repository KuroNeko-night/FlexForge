/**
 * XLSX 导出（P23，FR-META-06）：与 CSV 同口径的本地导出（当前已加载记录、
 * 无网络请求、无新端点）。exceljs（MIT，docs/13 §3.9）构建工作簿；
 * 单元格口径与 utils/csv 一致（boolean→是/否，null→空串）。
 */
import ExcelJS from 'exceljs';

/** 单元格口径：与 csv.cell 一致（null/undefined→空串；boolean→是/否；其余 String()）。 */
function cell(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否';
  }
  return String(value);
}

/** sheet 名清洗：Excel 上限 31 字符，替换非法字符 []:*?/\（实体显示名来自插件元数据，不可信输入）。 */
export function xlsxSafeSheetName(name: string): string {
  const cleaned = name
    .replace(/[[\]:*?/\\]/g, '-')
    .trim()
    .slice(0, 31);
  return cleaned === '' ? '导出' : cleaned;
}

/** 组装 XLSX 工作簿二进制（首行表头；表头行加粗便于阅读）。 */
export async function buildXlsx(
  sheetName: string,
  headers: string[],
  rows: unknown[][],
): Promise<ArrayBuffer> {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet(xlsxSafeSheetName(sheetName));
  sheet.addRow(headers.map(cell));
  headers.forEach((_, index) => {
    sheet.getRow(1).getCell(index + 1).font = { bold: true };
  });
  rows.forEach((row) => sheet.addRow(row.map(cell)));
  const buffer = await workbook.xlsx.writeBuffer();
  return buffer as ArrayBuffer;
}

/** 触发浏览器下载（xlsx MIME）。revoke 延迟同 CSV 口径（Safari 截断防护）。 */
export async function downloadXlsx(filename: string, data: ArrayBuffer): Promise<void> {
  const blob = new Blob([data], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 4000);
}
