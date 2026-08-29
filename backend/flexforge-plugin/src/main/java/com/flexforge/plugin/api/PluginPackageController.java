package com.flexforge.plugin.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.auth.core.AuthService;
import com.flexforge.common.ApiConstants;
import com.flexforge.common.PublicApi;
import com.flexforge.common.api.ErrorCodes;
import com.flexforge.plugin.application.PluginImportService;
import com.flexforge.plugin.application.PluginInventoryService;
import com.flexforge.plugin.domain.InstallPreview;
import com.flexforge.plugin.domain.PluginValidationException;
import com.flexforge.plugin.domain.ValidationReport;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 插件包上传接口（FR-PLUGIN-01/02，仅管理员）：multipart zip 包。
 * docs/13 §3.5：Content-Type 检查 + zip 魔数嗅探（ArchiveInspector），
 * 不信任客户端文件名；大小上限由 multipart 配置与显式检查双层约束。
 */
@PublicApi
@RestController
@RequestMapping(ApiConstants.API_V1 + "/plugins")
public class PluginPackageController {

    private static final Set<String> ZIP_CONTENT_TYPES = Set.of(
            "application/zip", "application/x-zip-compressed", "application/octet-stream");

    private final PluginImportService imports;
    private final PluginInventoryService inventory;
    private final AuthService authService;

    public PluginPackageController(PluginImportService imports, PluginInventoryService inventory,
                                   AuthService authService) {
        this.imports = imports;
        this.inventory = inventory;
        this.authService = authService;
    }

    /** 插件清单（docs/03 §8 GET /plugins/inventory）：版本、激活与失败诊断。 */
    @GetMapping("/inventory")
    @RequireRole(Roles.ADMIN)
    public List<PluginInventoryService.PluginInventoryEntry> inventory() {
        return inventory.inventory();
    }

    @PostMapping(value = "/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(Roles.ADMIN)
    public ValidationReport validate(@RequestParam("file") MultipartFile file) throws IOException {
        return imports.validate(requireZip(file));
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequireRole(Roles.ADMIN)
    public InstallPreview importPackage(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @RequestParam("file") MultipartFile file) throws IOException {
        return imports.importPackage(actor(principal), requireZip(file));
    }

    private static byte[] requireZip(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new PluginValidationException(ErrorCodes.INVALID_MANIFEST, "未上传插件包文件");
        }
        String contentType = file.getContentType();
        if (contentType == null
                || !ZIP_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new PluginValidationException(ErrorCodes.INVALID_MANIFEST,
                    "Content-Type 须为 zip 系（收到 " + contentType + "）");
        }
        try {
            return file.getBytes();
        } catch (UncheckedIOException e) {
            throw new PluginValidationException(ErrorCodes.INVALID_MANIFEST, "插件包读取失败");
        }
    }

    private String actor(AuthPrincipal principal) {
        return authService.currentUser(principal).username();
    }
}
