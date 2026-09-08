package com.flexforge.plugin.application;

import com.flexforge.common.audit.AuditEventPort;
import com.flexforge.common.audit.AuditEvents;
import com.flexforge.common.api.ErrorCodes;
import com.flexforge.data.application.DynamicRecordService;
import com.flexforge.common.api.PageResult;
import com.flexforge.data.domain.RecordEntry;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.ProcessorExecutionException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 处理器编排（P20，extension.data-processor 责任模块）：清单查询（ACTIVE 激活
 * 展开，经 {@link ActiveProcessorLocator}）与 invoke——输入组装（经
 * service.data-access 白名单查询目标实体，行数/字节上限）→ {@link ProcessorRunner}
 * 受控执行 → 输出契约校验（table/summary/chart，登记册 §2.2，P22 增 chart）→ 结果返回并审计。
 */
@Service
public class ProcessorService {

    /** 输入上限（docs/09 P20 红线）：行数=平台单页上限（ApiConstants.MAX_PAGE_SIZE），
     * 序列化字节 ≤2MB。 */
    static final int MAX_INPUT_ROWS = com.flexforge.common.ApiConstants.MAX_PAGE_SIZE;
    static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_COLUMNS = 50;
    private static final int MAX_ROWS = 1000;
    private static final int MAX_ITEMS = 50;

    /** 单元格文本上限（审查 P3-4：防单个近 1MB 文本渲染进一个单元格）。 */
    private static final int MAX_CELL_TEXT = 2000;

    /** 图表契约上限（P22，FR-PLUGIN-13）：点位/标签/标题。 */
    private static final int MAX_CHART_POINTS = 50;
    private static final int MAX_CHART_LABEL = 100;
    private static final int MAX_CHART_TITLE = 200;

    private final ActiveProcessorLocator locator;
    private final DynamicRecordService records;
    private final ProcessorRunner runner;
    private final AuditEventPort audit;
    private final Clock clock;

    public ProcessorService(ActiveProcessorLocator locator, DynamicRecordService records,
                            ProcessorRunner runner, AuditEventPort audit, Clock clock) {
        this.locator = locator;
        this.records = records;
        this.runner = runner;
        this.audit = audit;
        this.clock = clock;
    }

    /** 处理器清单（可选按目标实体过滤）。 */
    public List<ProcessorView> listProcessors(String entity) {
        List<ProcessorView> result = new ArrayList<>();
        for (ActiveProcessorLocator.ActiveProcessor processor : locator.activeProcessors()) {
            if (entity == null || entity.isBlank()
                    || processor.spec().inputEntity().equals(entity)) {
                result.add(new ProcessorView(processor.spec().key(), processor.spec().label(),
                        processor.pluginId(), processor.spec().inputEntity()));
            }
        }
        return List.copyOf(result);
    }

    /** 执行：entity 必须与处理器声明的 inputEntity 一致（声明式输入契约）；
     * 成败均审计（docs/09 P20 验收②，审查 P2-4——超时等失败正是资源滥用信号）。 */
    public JsonNode invoke(String actor, String key, String entity) {
        try {
            JsonNode validated = doInvoke(key, entity);
            audit.record(AuditEvents.of(actor, "plugin.processor.invoke", key, "success", clock));
            return validated;
        } catch (RuntimeException e) {
            audit.record(AuditEvents.of(actor, "plugin.processor.invoke", key, "failure", clock));
            throw e;
        }
    }

    private JsonNode doInvoke(String key, String entity) {
        ActiveProcessorLocator.ActiveProcessor processor = locator.findByKey(key);
        if (!processor.spec().inputEntity().equals(entity)) {
            throw new PluginValidationException(ErrorCodes.VALIDATION_ERROR,
                    "处理器 " + key + " 声明的目标实体是 " + processor.spec().inputEntity()
                            + "，与请求实体 " + entity + " 不一致");
        }
        JsonNode output = runAndParse(processor.script(), assembleInput(entity));
        return OutputValidator.validate(output);
    }

    /** 执行并解析 stdout（非法 JSON 即 output_invalid）。 */
    private JsonNode runAndParse(byte[] script, String inputJson) {
        try {
            return JSON.readTree(runner.run(script, inputJson));
        } catch (tools.jackson.core.JacksonException e) {
            throw ProcessorExecutionException.outputInvalid("处理器输出不是合法 JSON");
        }
    }

    /** 输入组装：白名单查询目标实体（≤MAX_INPUT_ROWS）→ {records:[{...data}]}。 */
    private String assembleInput(String entity) {
        PageResult<RecordEntry> page = records.query(entity,
                new DynamicRecordService.DataQuery(1, MAX_INPUT_ROWS, null, null, Map.of()));
        if (page.total() > MAX_INPUT_ROWS) {
            throw ProcessorExecutionException.inputTooLarge(
                    "实体 " + entity + " 共 " + page.total() + " 条记录，超过处理器输入上限 "
                            + MAX_INPUT_ROWS + " 行");
        }
        ObjectNode input = JSON.createObjectNode();
        ArrayNode rows = input.putArray("records");
        for (RecordEntry entry : page.items()) {
            rows.add(entry.data());
        }
        String json = JSON.writeValueAsString(input);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
            throw ProcessorExecutionException.inputTooLarge(
                    "处理器输入序列化后超过 " + MAX_INPUT_BYTES + " 字节上限");
        }
        return json;
    }

    /** 清单视图（GET /plugins/processors 契约）。 */
    public record ProcessorView(String key, String label, String pluginId, String inputEntity) {
    }

    /** 输出契约校验（登记册 §2.2）：kind=table（columns/rows 定宽二维标量）、
     * kind=summary（items 标量）或 kind=chart（chartType bar|pie + categories/values，
     * FR-PLUGIN-13）；违约 processor_output_invalid。 */
    static final class OutputValidator {

        private OutputValidator() {
        }

        static JsonNode validate(JsonNode output) {
            if (output == null || !output.isObject()) {
                throw ProcessorExecutionException.outputInvalid("处理器输出必须是 JSON 对象");
            }
            String kind = output.path("kind").asString("");
            switch (kind) {
                case "table" -> requireTable(output);
                case "summary" -> requireSummary(output);
                case "chart" -> requireChart(output);
                default -> throw ProcessorExecutionException.outputInvalid(
                        "输出 kind 必须是 table、summary 或 chart: " + kind);
            }
            return output;
        }

        private static void requireTable(JsonNode output) {
            JsonNode columns = output.path("columns");
            requireColumns(columns);
            JsonNode rows = output.path("rows");
            if (!rows.isArray() || rows.size() > MAX_ROWS) {
                throw ProcessorExecutionException.outputInvalid(
                        "table.rows 必须是数组（≤" + MAX_ROWS + " 行）");
            }
            for (JsonNode row : rows) {
                requireRow(row, columns.size());
            }
        }

        private static void requireColumns(JsonNode columns) {
            if (!columns.isArray() || columns.isEmpty() || columns.size() > MAX_COLUMNS) {
                throw ProcessorExecutionException.outputInvalid(
                        "table.columns 必须是非空数组（≤" + MAX_COLUMNS + " 列）");
            }
            for (JsonNode column : columns) {
                if (column.path("name").asString("").isBlank()
                        || column.path("label").asString("").isBlank()) {
                    throw ProcessorExecutionException.outputInvalid(
                            "table.columns 每项须含非空 name 与 label");
                }
            }
        }

        private static void requireRow(JsonNode row, int columnCount) {
            if (!row.isArray() || row.size() != columnCount) {
                throw ProcessorExecutionException.outputInvalid("table.rows 行宽与列数不一致");
            }
            for (JsonNode cell : row) {
                if (!cell.isValueNode()) {
                    throw ProcessorExecutionException.outputInvalid("table 单元格必须是标量值");
                }
                if (cell.isTextual() && cell.asText().length() > MAX_CELL_TEXT) {
                    throw ProcessorExecutionException.outputInvalid(
                            "table 单元格文本超过 " + MAX_CELL_TEXT + " 字符上限");
                }
            }
        }

        private static void requireSummary(JsonNode output) {
            JsonNode items = output.path("items");
            if (!items.isArray() || items.isEmpty() || items.size() > MAX_ITEMS) {
                throw ProcessorExecutionException.outputInvalid(
                        "summary.items 必须是非空数组（≤" + MAX_ITEMS + " 项）");
            }
            for (JsonNode item : items) {
                if (item.path("label").asString("").isBlank() || !item.path("value").isValueNode()) {
                    throw ProcessorExecutionException.outputInvalid(
                            "summary.items 每项须含非空 label 与标量 value");
                }
            }
        }

        /** 图表契约（P22，FR-PLUGIN-13）：chartType bar|pie、标题、categories/values 逐点校验。 */
        private static void requireChart(JsonNode output) {
            requireChartHeader(output);
            JsonNode categories = output.path("categories");
            JsonNode values = output.path("values");
            if (!categories.isArray() || categories.isEmpty()
                    || categories.size() > MAX_CHART_POINTS) {
                throw ProcessorExecutionException.outputInvalid(
                        "chart.categories 必须是非空数组（≤" + MAX_CHART_POINTS + " 项）");
            }
            if (!values.isArray() || values.size() != categories.size()) {
                throw ProcessorExecutionException.outputInvalid(
                        "chart.values 必须是与 categories 等长的数字数组");
            }
            for (int i = 0; i < categories.size(); i++) {
                requireChartPoint(categories.get(i), values.get(i), "pie".equals(
                        output.path("chartType").asString()));
            }
        }

        /** 图表头校验：chartType 白名单与标题长度。 */
        private static void requireChartHeader(JsonNode output) {
            String chartType = output.path("chartType").asString("");
            if (!"bar".equals(chartType) && !"pie".equals(chartType)) {
                throw ProcessorExecutionException.outputInvalid(
                        "chart.chartType 必须是 bar 或 pie: " + chartType);
            }
            String title = output.path("title").asString("");
            if (title.isBlank() || title.length() > MAX_CHART_TITLE) {
                throw ProcessorExecutionException.outputInvalid(
                        "chart.title 必须是非空文本（≤" + MAX_CHART_TITLE + " 字符）");
            }
        }

        /** 单点校验：标签非空定长；值有限数字；饼图份额不允许负值。 */
        private static void requireChartPoint(JsonNode category, JsonNode value, boolean pie) {
            String label = category.asString("");
            if (!category.isTextual() || label.isBlank() || label.length() > MAX_CHART_LABEL) {
                throw ProcessorExecutionException.outputInvalid(
                        "chart.categories 每项须为非空文本（≤" + MAX_CHART_LABEL + " 字符）");
            }
            if (!value.isNumber() || !Double.isFinite(value.asDouble())) {
                throw ProcessorExecutionException.outputInvalid("chart.values 每项须为有限数字");
            }
            if (pie && value.asDouble() < 0) {
                throw ProcessorExecutionException.outputInvalid("饼图份额不允许负值");
            }
        }
    }
}
