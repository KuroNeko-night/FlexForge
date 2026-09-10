// @vitest-environment happy-dom
import ExcelJS from 'exceljs';
import { describe, expect, it, vi } from 'vitest';

import { buildXlsx, downloadXlsx, xlsxSafeSheetName } from '@/utils/xlsx';

describe('xlsxSafeSheetName（P23 sheet 名清洗）', () => {
  it('替换 Excel 非法字符并截断到 31 字符', () => {
    expect(xlsxSafeSheetName('采购[订单]:2026*?/\\')).toBe('采购-订单--2026----');
    expect(xlsxSafeSheetName('x'.repeat(40))).toHaveLength(31);
  });

  it('清洗后为空回退默认名', () => {
    expect(xlsxSafeSheetName('   ')).toBe('导出');
  });
});

describe('buildXlsx（P23 FR-META-06 工作簿组装）', () => {
  it('产出合法 xlsx 二进制（PK zip 头），表头与数据行经 exceljs 读回一致', async () => {
    const buffer = await buildXlsx(
      '库存项',
      ['物料', '数量', '启用'],
      [
        ['轴承 6204-2RS', 120, true],
        ['密封圈', null, false],
      ],
    );
    const bytes = new Uint8Array(buffer);
    expect(bytes[0]).toBe(0x50); // P
    expect(bytes[1]).toBe(0x4b); // K
    // 用 exceljs 自身读回校验内容（真实构造路径，不 mock 模块）
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(buffer);
    const sheet = workbook.worksheets[0];
    expect(sheet.name).toBe('库存项');
    expect(sheet.getRow(1).getCell(1).value).toBe('物料');
    expect(sheet.getRow(2).getCell(1).value).toBe('轴承 6204-2RS');
    expect(sheet.getRow(2).getCell(2).value).toBe('120');
    expect(sheet.getRow(2).getCell(3).value).toBe('是');
    expect(sheet.getRow(3).getCell(2).value).toBe('');
    expect(sheet.getRow(3).getCell(3).value).toBe('否');
    expect(sheet.rowCount).toBe(3);
  });
});

describe('downloadXlsx（P23 下载触发）', () => {
  it('以 xlsx MIME 触发下载并延迟 revoke', async () => {
    const created: { type: string; parts: BlobPart[] }[] = [];
    const revoked: string[] = [];
    const urls = ['blob:xlsx-1'];
    vi.stubGlobal(
      'Blob',
      vi.fn(function MockBlob(this: unknown[], parts: BlobPart[], options?: { type: string }) {
        created.push({ type: options?.type ?? '', parts });
        return this;
      }),
    );
    vi.stubGlobal(
      'URL',
      Object.assign(URL, {
        createObjectURL: vi.fn(() => urls[0]),
        revokeObjectURL: vi.fn((u: string) => revoked.push(u)),
      }),
    );
    const clicks: HTMLAnchorElement[] = [];
    const anchor = document.createElement('a');
    anchor.click = vi.fn(() => clicks.push(anchor));
    const creator = vi
      .spyOn(document, 'createElement')
      .mockImplementation((tag: string) => (tag === 'a' ? anchor : document.createElement(tag)));
    vi.spyOn(window, 'setTimeout').mockImplementation(((fn: () => void) => {
      fn();
      return 0;
    }) as unknown as typeof window.setTimeout);
    await downloadXlsx('库存项-导出.xlsx', new ArrayBuffer(8));
    expect(created[0]?.type).toBe(
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    );
    expect(anchor.download).toBe('库存项-导出.xlsx');
    expect(clicks).toHaveLength(1);
    expect(revoked).toEqual(['blob:xlsx-1']);
    creator.mockRestore();
    vi.unstubAllGlobals();
  });
});
