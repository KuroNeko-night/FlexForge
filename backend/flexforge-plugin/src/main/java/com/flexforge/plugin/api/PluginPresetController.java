package com.flexforge.plugin.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.plugin.application.PluginPresetService;
import com.flexforge.plugin.domain.PresetRepository.PresetRecord;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 插件预设接口（P21，FR-PLUGIN-12，仅管理员）：保存/清单/应用/删除。 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/plugins/presets")
public class PluginPresetController {

    private final PluginPresetService presets;
    private final AuthService authService;

    public PluginPresetController(PluginPresetService presets, AuthService authService) {
        this.presets = presets;
        this.authService = authService;
    }

    @GetMapping
    @RequireRole(Roles.ADMIN)
    public List<PresetRecord> list() {
        return presets.list();
    }

    /** 保存当前启用集合为命名预设（快照含版本）。 */
    @PostMapping
    @RequireRole(Roles.ADMIN)
    public PresetRecord save(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestBody SaveRequest request) {
        return presets.save(actor(principal), request.name());
    }

    /** 应用预设（收敛：停用预设外→按预设切换/激活；逐项结果上报）。 */
    @PostMapping("/{id}/apply")
    @RequireRole(Roles.ADMIN)
    public PluginPresetService.ApplyResult apply(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String id) {
        return presets.apply(actor(principal), id);
    }

    @DeleteMapping("/{id}")
    @RequireRole(Roles.ADMIN)
    public void delete(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String id) {
        presets.delete(actor(principal), id);
    }

    /** 保存请求体：仅 name 一个受控输入面。 */
    @PublicApi
    record SaveRequest(String name) {
    }

    private String actor(AuthPrincipal principal) {
        return authService.currentUser(principal).username();
    }
}
