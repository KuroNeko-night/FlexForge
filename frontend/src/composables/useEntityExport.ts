import { ref, type Ref } from 'vue';

import type { EntityDetail, RecordView, ViewDefinition } from '@/api/types';
import { buildCsv, csvSafeFilename, downloadCsv } from '@/utils/csv';
import { buildXlsx, downloadXlsx } from '@/utils/xlsx';
import { visibleColumns } from '@/utils/viewColumns';
import { t } from '@/registry/localeRegistry';

/**
 * 实体列表导出（P19 CSV / P23 XLSX）：同一口径——可见列 + 当前已加载记录，
 * 前端本地生成无网络请求；XLSX 构建失败给用户可见错误（审查 P3-10）。
 */
export function useEntityExport(options: {
  definition: Ref<EntityDetail | null>;
  listView: Ref<ViewDefinition | null>;
  records: Ref<RecordView[]>;
  entityName: Ref<string>;
}): { exportCsv: () => void; exportXlsx: () => Promise<void>; exportError: Ref<string | null> } {
  const exportError = ref<string | null>(null);

  function exportRows(): { headers: string[]; rows: unknown[][]; name: string } | null {
    const columns = visibleColumns(options.definition.value?.fields ?? [], options.listView.value);
    if (columns.length === 0) {
      return null;
    }
    return {
      headers: columns.map((field) => field.displayName),
      rows: options.records.value.map((record) =>
        columns.map((field) => record.data[field.name] ?? null),
      ),
      name: csvSafeFilename(options.definition.value?.displayName ?? options.entityName.value),
    };
  }

  function exportCsv(): void {
    const data = exportRows();
    if (data) {
      downloadCsv(
        `${data.name}-${t('export.suffix', '导出')}.csv`,
        buildCsv(data.headers, data.rows),
      );
    }
  }

  async function exportXlsx(): Promise<void> {
    const data = exportRows();
    if (!data) {
      return;
    }
    try {
      const workbook = await buildXlsx(data.name, data.headers, data.rows);
      await downloadXlsx(`${data.name}-${t('export.suffix', '导出')}.xlsx`, workbook);
    } catch {
      exportError.value = t('export.failed', '导出失败，请稍后重试');
    }
  }

  return { exportCsv, exportXlsx, exportError };
}
