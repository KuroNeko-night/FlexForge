package com.flexforge.ai.spec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RequirementSchema v1 契约（FR-ISSUE-04：AI 输出校验唯一事实源）。 */
class RequirementSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String VALID = """
            {
              "schemaVersion": 1,
              "summary": "库存管理",
              "entities": [{
                "name": "inventory_item",
                "displayName": "库存项",
                "fields": [
                  {"name": "sku", "displayName": "物料编码", "fieldType": "text",
                   "required": true, "validation": {"maxLength": 64}},
                  {"name": "qty", "displayName": "库存数量", "fieldType": "integer",
                   "required": true, "validation": {"min": 0}}
                ]
              }],
              "views": [{"entity": "inventory_item", "viewType": "list", "name": "库存列表",
                "columns": [{"field": "sku"}, {"field": "qty"}]}],
              "permissions": ["inventory.read", "inventory.write"],
              "rules": [{"name": "非负", "description": "库存数量不得小于 0"}],
              "acceptance": ["qty=-1 被拒绝", "列表可见"]
            }
            """;

    private static ObjectNode validSpec() {
        return (ObjectNode) JSON.readTree(VALID);
    }

    @Test
    void validSpecPasses() {
        assertThat(RequirementSchema.validate(validSpec())).isEmpty();
    }

    @Test
    void missingRequiredSectionsRejected() {
        List<String> errors = RequirementSchema.validate(JSON.readTree("{}"));
        assertThat(errors).anyMatch(e -> e.contains("schemaVersion"))
                .anyMatch(e -> e.contains("summary"))
                .anyMatch(e -> e.contains("entities"))
                .anyMatch(e -> e.contains("acceptance"));
    }

    @Test
    void emptyAcceptanceRejected() {
        ObjectNode spec = validSpec();
        spec.set("acceptance", JSON.createArrayNode());
        assertThat(RequirementSchema.validate(spec)).anyMatch(e -> e.contains("acceptance"));
    }

    @Test
    void unknownFieldTypeAndIllegalRuleRejected() {
        ObjectNode spec = validSpec();
        field(spec, 1).put("fieldType", "jsonblob");
        assertThat(RequirementSchema.validate(spec)).anyMatch(e -> e.contains("fieldType"));

        ObjectNode spec2 = validSpec();
        field(spec2, 1).set("validation", JSON.readTree("{\"regex\": \".*\"}"));
        assertThat(RequirementSchema.validate(spec2)).anyMatch(e -> e.contains("validation"));
    }

    @Test
    void duplicateEntityNameAndUnknownViewEntityRejected() {
        ObjectNode spec = validSpec();
        ArrayNode entities = spec.withArray("entities");
        ObjectNode first = (ObjectNode) entities.get(0);
        entities.add(first.deepCopy());
        assertThat(RequirementSchema.validate(spec)).anyMatch(e -> e.contains("重复"));

        ObjectNode spec2 = validSpec();
        ((ObjectNode) spec2.withArray("views").get(0)).put("entity", "ghost_entity");
        assertThat(RequirementSchema.validate(spec2)).anyMatch(e -> e.contains("未声明实体"));
    }

    @Test
    void viewColumnOutsideEntityFieldsRejected() {
        ObjectNode spec = validSpec();
        ObjectNode view = (ObjectNode) spec.withArray("views").get(0);
        view.withArray("columns").add(JSON.readTree("{\"field\": \"ghost\"}"));
        assertThat(RequirementSchema.validate(spec)).anyMatch(e -> e.contains("不存在的字段"));
    }

    private static ObjectNode field(ObjectNode spec, int index) {
        ObjectNode entity = (ObjectNode) spec.withArray("entities").get(0);
        return (ObjectNode) entity.withArray("fields").get(index);
    }
}
