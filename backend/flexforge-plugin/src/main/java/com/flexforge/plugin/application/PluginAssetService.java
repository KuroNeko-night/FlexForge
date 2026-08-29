package com.flexforge.plugin.application;

import com.flexforge.common.PublicApi;
import com.flexforge.plugin.domain.ActivationRecord;
import com.flexforge.plugin.domain.LifecycleRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Base64;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 插件静态资产读取（theme-asset serve 支撑，docs/13 §3.5 纵深第二层）：
 * 导入期已过白名单+魔数校验，本层按 activationId 做 stale 校验（FR-PLUGIN-07）
 * 后精确匹配 asset_payloads 键——无文件系统访问面，路径穿越无处生效；响应头
 * CSP/attachment/nosniff 由控制器附加（svg 事件属性纵深，Issue #20 第 4 项）。
 */
@PublicApi
@Service
public class PluginAssetService {

    /** 资产内容与媒体类型（扩展名白名单与 ArchiveInspector 资产区一致）。 */
    @PublicApi
    public record AssetContent(byte[] content, String contentType) {
    }

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            ".png", "image/png",
            ".svg", "image/svg+xml",
            ".webp", "image/webp",
            ".css", "text/css; charset=utf-8",
            ".json", "application/json");

    private final PluginLifecycleService lifecycle;
    private final LifecycleRepository repository;

    public PluginAssetService(PluginLifecycleService lifecycle, LifecycleRepository repository) {
        this.lifecycle = lifecycle;
        this.repository = repository;
    }

    /** 取当前激活版本的资产；未知路径/类型 → 404（不存在性泄露无敏感面）。 */
    public AssetContent assetOf(String activationId, String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.isBlank()) {
            throw new NoSuchElementException("资源不存在");
        }
        ActivationRecord activation = lifecycle.requireCurrentActivation(activationId);
        JsonNode assets = PluginContributionFactory.parseEntities(
                repository.assetPayloadsOf(activation.pluginVersionId()));
        JsonNode value = assets.get(normalized);
        if (value == null || !value.isTextual()) {
            throw new NoSuchElementException("资源不存在: " + normalized);
        }
        int dot = normalized.lastIndexOf('.');
        String contentType = CONTENT_TYPES.get(dot < 0 ? "" : normalized.substring(dot));
        if (contentType == null) {
            throw new NoSuchElementException("资源类型不在白名单: " + normalized);
        }
        byte[] content;
        try {
            content = Base64.getDecoder().decode(value.asText());
        } catch (IllegalArgumentException e) {
            // 存储内容损坏属服务端数据问题，不得误报为客户端 400
            throw new IllegalStateException("资产载荷损坏（base64 非法）: " + normalized, e);
        }
        return new AssetContent(content, contentType);
    }
}
