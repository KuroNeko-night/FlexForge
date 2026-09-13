package com.flexforge.kb.application;

import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.meta.application.MetaRegistry;
import com.flexforge.meta.domain.EntityDefinition;
import com.flexforge.meta.domain.EntityStatus;
import com.flexforge.meta.domain.FieldDefinition;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 助手业务检查工具（FR-KB-07，P30）：list_entities / inspect_entity——
 * 只读访问插件注册的业务实体元数据（含插件作者提供的 displayName）。
 * 平台内置示例演示（采购订单等既有业务插件自动可见）；插件作者经元数据
 * 命名质量自行适配（见开发 Skill），无需新增代码。
 */
@PublicApi
@Component
public class BusinessEntityTools {

    private final MetaRegistry registry;

    public BusinessEntityTools(MetaRegistry registry) {
        this.registry = registry;
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
}
