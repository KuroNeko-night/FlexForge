import { describe, expect, it } from 'vitest';

import { buildCsv, csvEscape } from '@/utils/csv';

describe('csvEscape（P19 RFC 4180 转义）', () => {
  it('普通文本原样输出', () => {
    expect(csvEscape('轴承 6204-2RS')).toBe('轴承 6204-2RS');
  });

  it('null/undefined/空串导出为空单元格', () => {
    expect(csvEscape(null)).toBe('');
    expect(csvEscape(undefined)).toBe('');
  });

  it('boolean 按中文口径导出（与 BooleanField display 一致）', () => {
    expect(csvEscape(true)).toBe('是');
    expect(csvEscape(false)).toBe('否');
  });

  it('数字转字符串', () => {
    expect(csvEscape(47200.5)).toBe('47200.5');
    expect(csvEscape(0)).toBe('0');
  });

  it('含逗号/引号/换行的字段加引号包裹且内部引号翻倍', () => {
    expect(csvEscape('铝型材,4040')).toBe('"铝型材,4040"');
    expect(csvEscape('说"没问题"')).toBe('"说""没问题"""');
    expect(csvEscape('第一行\n第二行')).toBe('"第一行\n第二行"');
  });
});

describe('buildCsv（P19 文档组装）', () => {
  it('首行表头，数据行按序，CRLF 行尾', () => {
    const csv = buildCsv(
      ['物料', '数量'],
      [
        ['轴承', 120],
        ['密封圈', null],
      ],
    );
    expect(csv).toBe('物料,数量\r\n轴承,120\r\n密封圈,\r\n');
  });

  it('表头同样参与转义', () => {
    const csv = buildCsv(['备注,重要'], [['值']]);
    expect(csv.startsWith('"备注,重要"\r\n')).toBe(true);
  });
});
