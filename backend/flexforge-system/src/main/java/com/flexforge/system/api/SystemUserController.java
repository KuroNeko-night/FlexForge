package com.flexforge.system.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.common.api.PageQuery;
import com.flexforge.common.api.PageResult;
import com.flexforge.system.application.UserAdminService;
import com.flexforge.system.infrastructure.UserAdminRecord;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户与角色管理接口（FR-AUTH-03，仅管理员）：创建用户、分配角色、分页列表。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/system/users")
public class SystemUserController {

    public record CreateUserRequest(String username, String password, String displayName,
                                    List<String> roles) {
    }

    public record UpdateRolesRequest(List<String> roles) {
    }

    public record UserView(long id, String username, String displayName, String status,
                           List<String> roles) {
    }

    private final UserAdminService userAdminService;

    public SystemUserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @PostMapping
    @RequireRole(Roles.ADMIN)
    public UserView create(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                           @RequestBody CreateUserRequest request) {
        UserAdminRecord created = userAdminService.createUser(principal.userId(),
                request.username(), request.password(), request.displayName(), request.roles());
        return toView(created);
    }

    @PutMapping("/{id}/roles")
    @RequireRole(Roles.ADMIN)
    public UserView assignRoles(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                                @PathVariable long id, @RequestBody UpdateRolesRequest request) {
        UserAdminRecord updated = userAdminService.assignRoles(principal.userId(), id, request.roles());
        return toView(updated);
    }

    @GetMapping
    @RequireRole(Roles.ADMIN)
    public PageResult<UserView> list(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int pageSize,
                                     @RequestParam(required = false) String sortBy,
                                     @RequestParam(required = false) PageQuery.SortDirection sortDirection) {
        PageResult<UserAdminRecord> result = userAdminService.listUsers(page, pageSize, sortBy,
                sortDirection);
        List<UserView> views = result.items().stream()
                .map(SystemUserController::toView).toList();
        return new PageResult<>(views, result.total(), result.pageNumber(), result.pageSize());
    }

    private static UserView toView(UserAdminRecord record) {
        return new UserView(record.id(), record.username(), record.displayName(), record.status(),
                record.roles());
    }
}
