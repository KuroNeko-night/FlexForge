package com.flexforge.common.api;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PageQuery 契约回归（审计 P2-4：边界与白名单失败路径必须有断言）。
 */
class PageQueryTest {

    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "name");

    @Test
    void firstPageUsesFrozenDefaults() {
        PageQuery query = PageQuery.firstPage();

        assertThat(query.pageNumber()).isEqualTo(1);
        assertThat(query.pageSize()).isEqualTo(20);
        assertThat(query.sortBy()).isNull();
        assertThat(query.sortDirection()).isEqualTo(PageQuery.SortDirection.ASC);
    }

    @Test
    void whitelistedSortIsAccepted() {
        PageQuery query = PageQuery.of(2, 50, "createdAt", PageQuery.SortDirection.DESC, SORT_FIELDS);

        assertThat(query.sortBy()).isEqualTo("createdAt");
        assertThat(query.sortDirection()).isEqualTo(PageQuery.SortDirection.DESC);
    }

    @Test
    void nullSortByDefaultsToAscWithoutWhitelistCheck() {
        PageQuery query = PageQuery.of(1, 20, null, null, null);

        assertThat(query.sortBy()).isNull();
        assertThat(query.sortDirection()).isEqualTo(PageQuery.SortDirection.ASC);
    }

    @Test
    void nonNullSortByRequiresDirection() {
        assertThatThrownBy(() -> PageQuery.of(1, 20, "createdAt", null, SORT_FIELDS))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sortDirection");
    }

    @Test
    void sortByOutsideWhitelistIsRejected() {
        assertThatThrownBy(() -> PageQuery.of(1, 20, "password; DROP TABLE", PageQuery.SortDirection.ASC, SORT_FIELDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("白名单");
        assertThatThrownBy(() -> PageQuery.of(1, 20, "createdAt", PageQuery.SortDirection.ASC, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageNumberBelowOneIsRejectedIncludingDirectConstruction() {
        assertThatThrownBy(() -> PageQuery.of(0, 20, null, null, SORT_FIELDS))
                .isInstanceOf(IllegalArgumentException.class);
        // 规范构造器同样强制：new 无法绕过数值边界
        assertThatThrownBy(() -> new PageQuery(0, 20, null, PageQuery.SortDirection.ASC))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageSizeOutsideBoundsIsRejectedIncludingDirectConstruction() {
        assertThatThrownBy(() -> PageQuery.of(1, 0, null, null, SORT_FIELDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageQuery.of(1, 201, null, null, SORT_FIELDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("201");
        assertThatThrownBy(() -> new PageQuery(1, 201, null, PageQuery.SortDirection.ASC))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
