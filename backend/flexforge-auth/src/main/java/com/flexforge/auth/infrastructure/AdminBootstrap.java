package com.flexforge.auth.infrastructure;

import com.flexforge.auth.AuthProperties;
import com.flexforge.auth.Roles;
import com.flexforge.auth.core.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 引导管理员：仅当 sys_user 为空且显式提供 flexforge.auth.bootstrap-admin-password 时创建
 * ADMIN 用户（S4：初始密码仅环境变量注入，不落仓库；不提供则跳过，由部署方自行管理）。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcUserRepository users;
    private final PasswordHasher passwordHasher;
    private final AuthProperties properties;

    public AdminBootstrap(JdbcUserRepository users, PasswordHasher passwordHasher,
                          AuthProperties properties) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = properties.getBootstrapAdminPassword();
        if (password == null || password.isBlank()) {
            log.info("未配置引导管理员密码（flexforge.auth.bootstrap-admin-password），跳过引导");
            return;
        }
        if (users.countUsers() > 0) {
            log.info("已存在用户，跳过引导管理员");
            return;
        }
        users.insertUserWithRoles(properties.getBootstrapAdminUsername(),
                passwordHasher.hash(password), "系统管理员", List.of(Roles.ADMIN));
        log.info("引导管理员已创建：{}", properties.getBootstrapAdminUsername());
    }
}
