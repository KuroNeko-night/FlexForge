package com.flexforge.ai.config;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模型运行时配置接口（FR-SETUP-01，docs/03 §8）：读回掩码视图（无密钥明文），
 * 更新经服务层校验+加密+审计；仅 ADMIN（S2 服务端授权）。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/ai")
public class AiConfigController {

    private final AiConfigService configs;
    private final AuthService authService;

    public AiConfigController(AiConfigService configs, AuthService authService) {
        this.configs = configs;
        this.authService = authService;
    }

    public record UpdateAiConfigRequest(String provider, String baseUrl, String model,
                                        String apiKey, Boolean clearApiKey) {
    }

    @GetMapping("/config")
    @RequireRole(Roles.ADMIN)
    public AiConfigService.ConfigView get() {
        return configs.view();
    }

    @PutMapping("/config")
    @RequireRole(Roles.ADMIN)
    public AiConfigService.ConfigView update(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestBody UpdateAiConfigRequest request) {
        String operator = authService.currentUser(principal).username();
        configs.update(operator, new AiConfigService.UpdateCommand(
                request.provider(), request.baseUrl(), request.model(),
                request.apiKey(), request.clearApiKey()));
        return configs.view();
    }
}
