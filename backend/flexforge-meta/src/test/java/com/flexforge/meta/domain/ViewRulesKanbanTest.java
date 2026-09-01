package com.flexforge.meta.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * kanban 视图分列字段校验（docs/09 P17）：groupBy 必填、必须是同实体字段且
 * 为 enum 类型——列来源完全由声明给出（枚举选项），无脚本语义（S5）。
 */
class ViewRulesKanbanTest {

    private static final Set<String> FIELDS = Set.of("title", "stage", "owner");
    private static final Set<String> ENUMS = Set.of("stage");

    @Test
    void validGroupByEnumFieldPasses() {
        assertThatCode(() -> ViewRules.validateKanban("stage", FIELDS, ENUMS))
                .doesNotThrowAnyException();
    }

    @Test
    void missingGroupByRejected() {
        assertThatThrownBy(() -> ViewRules.validateKanban(null, FIELDS, ENUMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须声明 groupBy");
        assertThatThrownBy(() -> ViewRules.validateKanban("  ", FIELDS, ENUMS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownFieldRejected() {
        assertThatThrownBy(() -> ViewRules.validateKanban("missing", FIELDS, ENUMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在的字段");
    }

    @Test
    void nonEnumFieldRejected() {
        assertThatThrownBy(() -> ViewRules.validateKanban("title", FIELDS, ENUMS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enum 类型");
    }
}
