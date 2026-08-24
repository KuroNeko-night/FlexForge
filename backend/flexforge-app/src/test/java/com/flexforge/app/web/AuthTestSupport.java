package com.flexforge.app.web;

import com.jayway.jsonpath.JsonPath;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 认证测试支撑：幂等种子用户（三角色）与 MockMvc 登录换令牌助手（P03 迭代 1）。
 * 口令为运行时按用户名派生的测试专用值（非真实凭据，仅存在于 Testcontainers 一次性数据库），
 * BCrypt 哈希在种子时现场计算（S3 口径）。
 */
public final class AuthTestSupport {

    public static final String ADMIN_USERNAME = "test-admin";
    public static final String DEVELOPER_USERNAME = "test-developer";
    public static final String USER_USERNAME = "test-user";

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(10);

    private AuthTestSupport() {
    }

    /** 测试口令按用户名派生，源码中不落任何口令字面量。 */
    private static String passwordFor(String username) {
        return "test-pass-" + username;
    }

    public static String adminPassword() {
        return passwordFor(ADMIN_USERNAME);
    }

    public static String developerPassword() {
        return passwordFor(DEVELOPER_USERNAME);
    }

    public static String userPassword() {
        return passwordFor(USER_USERNAME);
    }

    /** 幂等写入三角色种子用户（共享测试容器中可重复调用）。 */
    public static void seedUsers(JdbcTemplate jdbc) {
        seedUser(jdbc, ADMIN_USERNAME, "测试管理员", "ADMIN");
        seedUser(jdbc, DEVELOPER_USERNAME, "测试开发者", "DEVELOPER");
        seedUser(jdbc, USER_USERNAME, "测试用户", "USER");
    }

    private static void seedUser(JdbcTemplate jdbc, String username, String displayName, String role) {
        jdbc.update("INSERT INTO sys_user (username, password_hash, display_name) VALUES (?, ?, ?)"
                        + " ON CONFLICT (username) DO NOTHING",
                username, ENCODER.encode(passwordFor(username)), displayName);
        jdbc.update("INSERT INTO sys_user_role (user_id, role_id)"
                        + " SELECT u.id, r.id FROM sys_user u, sys_role r WHERE u.username = ? AND r.code = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM sys_user_role ur"
                        + " WHERE ur.user_id = u.id AND ur.role_id = r.id)",
                username, role);
    }

    /** 登录并返回 Bearer 令牌；失败直接让测试失败。 */
    public static String loginToken(MockMvc mockMvc, String username, String password) {
        try {
            String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                            .contentType("application/json")
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return JsonPath.read(body, "$.token");
        } catch (Exception e) {
            throw new IllegalStateException("测试登录失败: " + username, e);
        }
    }
}
