package com.flexforge.auth;

import com.flexforge.common.PublicApi;

import java.util.List;

/**
 * 平台角色常量（V003 种子与 docs/02 §1 一一对应）：管理员/开发者/普通用户。
 * 接口与菜单按角色差异见 docs/09 P03（迭代 2 落地管理接口）。
 */
@PublicApi
public final class Roles {

    /** 管理员：管理用户角色、插件生命周期和系统审计。 */
    public static final String ADMIN = "ADMIN";

    /** 开发者：创建实体/字段/视图配置。 */
    public static final String DEVELOPER = "DEVELOPER";

    /** 普通用户：使用动态实体数据。 */
    public static final String USER = "USER";

    /** 平台角色全集。 */
    public static final List<String> ALL = List.of(ADMIN, DEVELOPER, USER);

    private Roles() {
    }
}
