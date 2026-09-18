package com.flexforge.kb.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.data.application.DynamicRecordService;
import com.flexforge.data.domain.RecordEntry;
import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 助手业务检查与数据查询工具（FR-KB-07/08，P30-P31）：list_entities /
 * inspect_entity——只读访问插件注册的业务实体元数据（含插件作者提供的
 * displayName）；query_records——经 DynamicRecordService.query 只读查询启用
 * 实体的真实记录（过滤走平台白名单解析，无新 SQL 面，见 docs/13 §3.6-11）。
 * 平台内置示例演示（采购订单等既有业务插件自动可见）；插件作者经元数据
 * 命名质量自行适配（见开发 Skill），无需新增代码。
 */
@PublicApi
@Component
public class BusinessEntityTools {

    private final MetaRegistry registry;
    private final DynamicRecordService records;

    public BusinessEntityTools(MetaRegistry registry, DynamicRecordService records) {
        this.registry = registry;
        this.records = records;
    }

    /** 业务实体索引（进提示词数据段："显示名(name)" 顿号连接；
     * 超过 50 条静默截断——追加省略标记让模型知情，审查 P3-6）。 */
    static final int INDEX_LIMIT = 50;

    public String entityIndex() {
        StringBuilder index = new StringBuilder();
        var items = registry.listEntities(
                PageQuery.of(1, INDEX_LIMIT, null, java.util.Set.of()), EntityStatus.ENABLED).items();
        for (var record : items) {
            if (index.length() > 0) {
                index.append("、");
            }
            index.append(record.displayName()).append('(').append(record.name()).append(')');
        }
        if (index.length() == 0) {
            return "（暂无业务实体）";
        }
        if (items.size() >= INDEX_LIMIT) {
            index.append("（仅列前 ").append(INDEX_LIMIT).append(" 个）");
        }
        return index.toString();
    }

    /** list_entities：列出全部业务实体（显示名/name/来源插件）。 */
    @PublicApi
    @Component
    public static class ListEntitiesTool implements AssistantTool {

        private final BusinessEntityTools tools;

        public ListEntitiesTool(BusinessEntityTools tools) {
            this.tools = tools;
        }

        @Override
        public String name() {
            return "list_entities";
        }

        @Override
        public String description() {
            return "列出平台当前有哪些业务（业务实体显示名与名称，无参数）";
        }

        @Override
        public String apply(Map<String, String> parameters) {
            return tools.entityIndex();
        }
    }

    /** inspect_entity：查看某业务实体的字段结构（entity=实体名如 purchase_order）。 */
    @PublicApi
    @Component
    public static class InspectEntityTool implements AssistantTool {

        static final String ENTITY_PARAMETER = "entity";

        private final BusinessEntityTools tools;

        public InspectEntityTool(BusinessEntityTools tools) {
            this.tools = tools;
        }

        @Override
        public String name() {
            return "inspect_entity";
        }

        @Override
        public String description() {
            return "查看某个业务的字段结构（参数 entity=实体名，如 purchase_order）";
        }

        @Override
        public String apply(Map<String, String> parameters) {
            String entityName = parameters.getOrDefault(ENTITY_PARAMETER, "").strip();
            if (entityName.isEmpty()) {
                throw new IllegalArgumentException("缺少 entity 参数（实体名）");
            }
            return tools.inspect(entityName);
        }
    }

    String inspect(String entityName) {
        EntityDefinition definition = registry.findEntityByName(entityName)
                .orElseThrow(() -> new IllegalArgumentException("业务实体不存在: " + entityName));
        // 与索引同口径只暴露启用中的业务（审查 P3-5：停用实体不进助手工具面）
        if (definition.status() != EntityStatus.ENABLED) {
            throw new IllegalArgumentException("业务实体不存在: " + entityName);
        }
        StringBuilder out = new StringBuilder("业务：").append(definition.displayName())
                .append('(').append(definition.name()).append(')');
        if (definition.pluginId() != null) {
            out.append("，来源插件：").append(definition.pluginId());
        }
        out.append('\n');
        for (FieldDefinition field : definition.fields()) {
            out.append("- ").append(field.displayName()).append('(').append(field.name())
                    .append(")：").append(field.fieldType())
                    .append(field.required() ? "，必填" : "").append('\n');
        }
        if (!definition.views().isEmpty()) {
            out.append("视图：").append(definition.views().size()).append(" 个");
        }
        return out.toString();
    }

    /** query_records 记录预算（docs/13 §3.6-11：防以问答为放大器的数据拖库）。 */
    static final int RECORDS_DEFAULT_LIMIT = 5;
    static final int RECORDS_MAX_LIMIT = 10;
    static final int RECORDS_VALUE_MAX_CHARS = 60;
    static final int RECORDS_TOTAL_MAX_CHARS = 1600;

    /** query_records：按创建时间倒序查询启用实体的真实记录（field/op/value 可选
     * 单条件过滤——字段/操作符/类型兼容由 DataQueryParams 白名单校验，非法值
     * IAE 回灌提示词；总输出超预算截断并按实际渲染行数标注）。 */
    String queryRecords(String entityName, String field, String op, String value, int limit) {
        if (entityName == null || entityName.isBlank()) {
            throw new IllegalArgumentException("缺少 entity 参数（实体名）");
        }
        String name = entityName.strip();
        Map<String, String> filters = filtersOf(field, op, value);
        int pageSize = pageSizeOf(limit);
        PageResult<RecordEntry> page;
        EntityDefinition definition;
        try {
            page = records.query(name, new DynamicRecordService.DataQuery(
                    1, pageSize, DynamicRecordService.SORT_CREATED_AT,
                    PageQuery.SortDirection.DESC, filters));
            // 渲染需要字段 displayName；查询成功后实体被并发停用的窄窗口也按
            // 不存在回灌（审查 P3-1：裸 NSEE 会击穿 IAE 包装直达 500）
            definition = registry.findEntityByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("业务实体不存在: " + name));
        } catch (NoSuchElementException e) {
            // 与 inspect 同口径：停用/不存在一律按不存在回灌（不泄露存在性）
            throw new IllegalArgumentException("业务实体不存在: " + name);
        }
        String head = "业务数据：" + definition.displayName() + '(' + definition.name() + ')';
        if (page.total() == 0) {
            return head + " 暂无记录。";
        }
        StringBuilder out = new StringBuilder(head)
                .append(" 共 ").append(page.total()).append(" 条记录（按创建时间倒序）：\n");
        int rendered = 0;
        for (RecordEntry record : page.items()) {
            String line = "- " + recordLine(definition, record) + '\n';
            if (out.length() + line.length() > RECORDS_TOTAL_MAX_CHARS) {
                // 截断标注按实际渲染行数报数，与头部不矛盾（审查 P3-6）
                out.append("（仅显示前 ").append(rendered).append(" 条，其余已省略）\n");
                break;
            }
            out.append(line);
            rendered++;
        }
        return out.toString();
    }

    /** 单条件过滤参数（"字段.操作符"→值；op 缺省 contains；field/value 须成对，
     *  只给其一按参数错误回灌——静默丢弃会让模型误以为过滤已生效，审查 P3-6）。 */
    private static Map<String, String> filtersOf(String field, String op, String value) {
        boolean hasField = field != null && !field.isBlank();
        boolean hasValue = value != null && !value.isBlank();
        if (hasField && !hasValue) {
            throw new IllegalArgumentException("带 field 过滤时缺少 value 参数（过滤值）");
        }
        if (!hasField && hasValue) {
            throw new IllegalArgumentException("带 value 过滤时缺少 field 参数（字段名）");
        }
        if (!hasField) {
            return Map.of();
        }
        String operator = op == null || op.isBlank() ? "contains" : op.strip();
        return Map.of(field.strip() + "." + operator, value.strip());
    }

    /** 条数上限（缺省 5，界 [1, RECORDS_MAX_LIMIT]）。 */
    private static int pageSizeOf(int limit) {
        int requested = limit <= 0 ? RECORDS_DEFAULT_LIMIT : limit;
        return Math.min(Math.max(requested, 1), RECORDS_MAX_LIMIT);
    }

    /** 单条记录行：编号 + 实体字段序的「显示名=值」（null/缺失字段跳过，值截断）。 */
    private String recordLine(EntityDefinition definition, RecordEntry record) {
        StringBuilder line = new StringBuilder("编号 ").append(record.id());
        for (FieldDefinition field : definition.fields()) {
            String rendered = scalarText(record.data().get(field.name()));
            if (rendered == null || rendered.isEmpty()) {
                continue;
            }
            if (rendered.codePointCount(0, rendered.length()) > RECORDS_VALUE_MAX_CHARS) {
                rendered = rendered.substring(0,
                        rendered.offsetByCodePoints(0, RECORDS_VALUE_MAX_CHARS)) + "…";
            }
            line.append("，").append(field.displayName()).append('=').append(rendered);
        }
        return line.toString();
    }

    /** 字段值渲染：标量原样、数组顿号连接（null 项跳过，审查 P3-6）、对象紧凑 JSON
     *  （null/缺失返回 null）。 */
    private static String scalarText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode child : node) {
                if (child.isNull()) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append('、');
                }
                joined.append(child.isValueNode() ? child.asString() : child.toString());
            }
            return joined.toString();
        }
        return node.isValueNode() ? node.asString() : node.toString();
    }

    /** query_records：查询业务实体的真实记录（entity 必填；field/op/value/limit
     *  可选——field 过滤时 value 必填，op 缺省 contains，limit 缺省 5 上限 10）。 */
    @PublicApi
    @Component
    public static class QueryRecordsTool implements AssistantTool {

        static final String ENTITY_PARAMETER = "entity";
        static final String FIELD_PARAMETER = "field";
        static final String OP_PARAMETER = "op";
        static final String VALUE_PARAMETER = "value";
        static final String LIMIT_PARAMETER = "limit";

        private final BusinessEntityTools tools;

        public QueryRecordsTool(BusinessEntityTools tools) {
            this.tools = tools;
        }

        @Override
        public String name() {
            return "query_records";
        }

        @Override
        public String description() {
            return "查询某个业务的真实数据记录（参数 entity=实体名；可选 field/op/value 单条件"
                    + "过滤[op 为 eq/contains/gte/lte]、limit=条数上限 10；按创建时间倒序）";
        }

        @Override
        public String apply(Map<String, String> parameters) {
            return tools.queryRecords(parameters.getOrDefault(ENTITY_PARAMETER, ""),
                    parameters.getOrDefault(FIELD_PARAMETER, ""),
                    parameters.getOrDefault(OP_PARAMETER, ""),
                    parameters.getOrDefault(VALUE_PARAMETER, ""),
                    parseLimit(parameters.get(LIMIT_PARAMETER)));
        }

        private static int parseLimit(String raw) {
            if (raw == null || raw.isBlank()) {
                return RECORDS_DEFAULT_LIMIT;
            }
            try {
                return Integer.parseInt(raw.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("limit 参数须为整数: " + raw);
            }
        }
    }
}
