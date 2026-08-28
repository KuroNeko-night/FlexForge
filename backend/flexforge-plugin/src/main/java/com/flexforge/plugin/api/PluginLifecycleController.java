package com.flexforge.plugin.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.plugin.application.PluginLifecycleService;
import com.flexforge.plugin.domain.ActivationRecord;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 插件生命周期接口（FR-PLUGIN-02，仅管理员）：激活/停用/升级/卸载。 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/plugins")
public class PluginLifecycleController {

    private final PluginLifecycleService lifecycle;
    private final AuthService authService;

    public PluginLifecycleController(PluginLifecycleService lifecycle, AuthService authService) {
        this.lifecycle = lifecycle;
        this.authService = authService;
    }

    /** 激活指定版本（docs/03 §8 POST /plugins/{id}/activate）。 */
    @PostMapping("/{versionId}/activate")
    @RequireRole(Roles.ADMIN)
    public ActivationRecord activate(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String versionId) {
        return lifecycle.activate(actor(principal), versionId);
    }

    /** 停用当前激活（docs/03 §8 POST /plugins/{id}/stop）。 */
    @PostMapping("/{activationId}/stop")
    @RequireRole(Roles.ADMIN)
    public ActivationRecord stop(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String activationId) {
        return lifecycle.stop(actor(principal), activationId);
    }

    /** 升级到新版本（旧版本失败后保持可用）。 */
    @PostMapping("/{newVersionId}/upgrade")
    @RequireRole(Roles.ADMIN)
    public ActivationRecord upgrade(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String newVersionId) {
        return lifecycle.upgrade(actor(principal), newVersionId);
    }

    /** 卸载插件（停用语义+审计保留）。 */
    @DeleteMapping("/{pluginId}")
    @RequireRole(Roles.ADMIN)
    public void uninstall(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String pluginId) {
        lifecycle.uninstall(actor(principal), pluginId);
    }

    private String actor(AuthPrincipal principal) {
        return authService.currentUser(principal).username();
    }
}
