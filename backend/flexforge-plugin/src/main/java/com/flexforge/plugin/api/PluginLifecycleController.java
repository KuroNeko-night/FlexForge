package com.flexforge.plugin.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.plugin.application.PluginAssetService;
import com.flexforge.plugin.application.PluginInventoryService;
import com.flexforge.plugin.application.PluginLifecycleService;
import com.flexforge.plugin.domain.ActivationRecord;
import com.flexforge.plugin.domain.LifecycleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 插件生命周期接口（FR-PLUGIN-02，仅管理员）：激活/停用/升级/卸载/注册清单/资产。 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/plugins")
public class PluginLifecycleController {

    private final PluginLifecycleService lifecycle;
    private final PluginAssetService assets;
    private final AuthService authService;
    private final PluginInventoryService inventory;

    public PluginLifecycleController(PluginLifecycleService lifecycle, PluginAssetService assets,
                                     AuthService authService, PluginInventoryService inventory) {
        this.lifecycle = lifecycle;
        this.assets = assets;
        this.authService = authService;
        this.inventory = inventory;
    }

    /** 当前生效主题资产（P12.5，docs/09）：登录即可读——前端壳层换肤消费面。 */
    @GetMapping("/theme-assets")
    public List<PluginInventoryService.ActiveThemeAsset> themeAssets() {
        return inventory.activeThemeAssets();
    }

    /** 激活注册清单（FR-PLUGIN-07：stale activation 校验消费方，旧 ID 返回 409）。 */
    @GetMapping("/activations/{activationId}/registrations")
    @RequireRole(Roles.ADMIN)
    public List<LifecycleRepository.RegistrationEntry> registrations(
            @PathVariable String activationId) {
        return lifecycle.registrationsOf(activationId);
    }

    /**
     * 插件静态资产（theme-asset 消费面，登录即可读）：CSP default-src 'none' +
     * attachment + nosniff 三重纵深（svg 事件属性不执行，Issue #20 第 4 项；
     * img/CSS url() 加载不受 attachment 影响）。
     */
    @GetMapping("/activations/{activationId}/assets/{*path}")
    public ResponseEntity<byte[]> asset(@PathVariable String activationId,
                                        @PathVariable String path) {
        PluginAssetService.AssetContent asset = assets.assetOf(activationId, path);
        return ResponseEntity.ok()
                .header("Content-Type", asset.contentType())
                .header("Content-Security-Policy", "default-src 'none'")
                .header("Content-Disposition", "attachment")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=3600")
                .body(asset.content());
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
