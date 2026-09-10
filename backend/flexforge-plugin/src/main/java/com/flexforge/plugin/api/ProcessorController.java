package com.flexforge.plugin.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
import com.flexforge.plugin.application.FileProcessorService;
import com.flexforge.plugin.application.ProcessorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 数据处理器端点（P20，extension.data-processor 消费面）：清单查询与 invoke。
 * invoke 与动态数据读同权（ADMIN/USER，ADR-0002 Level 2：输入即调用者可读数据，
 * 处理器不引入新数据面）；导入仍 ADMIN 特权。
 */
@RestController
@RequestMapping("/api/v1/plugins/processors")
public class ProcessorController {

    private final ProcessorService processors;
    private final com.flexforge.auth.core.AuthService authService;
    private final com.flexforge.plugin.application.FileProcessorService fileProcessors;

    public ProcessorController(ProcessorService processors,
                               com.flexforge.auth.core.AuthService authService,
                               com.flexforge.plugin.application.FileProcessorService fileProcessors) {
        this.processors = processors;
        this.authService = authService;
        this.fileProcessors = fileProcessors;
    }

    /** 处理器清单（可选 ?entity= 过滤目标实体）。 */
    @GetMapping
    public List<ProcessorService.ProcessorView> list(@RequestParam(required = false) String entity) {
        return processors.listProcessors(entity);
    }

    /** 执行处理器：body {entity}（必须与处理器声明一致）。 */
    @PostMapping("/{key}/invoke")
    @RequireRole({Roles.ADMIN, Roles.USER})
    public JsonNode invoke(@RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
                           @PathVariable String key,
                           @RequestBody InvokeRequest request) {
        return processors.invoke(authService.currentUser(principal).username(),
                key, request.entity());
    }

    /** 执行文件输入处理器（P23，FR-PLUGIN-14）：multipart file；三重校验→受控执行。 */
    @PostMapping(value = "/{key}/invoke-file", consumes = "multipart/form-data")
    @RequireRole({Roles.ADMIN, Roles.USER})
    public JsonNode invokeFile(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @PathVariable String key,
            @org.springframework.web.bind.annotation.RequestParam("file")
            org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return fileProcessors.invokeFile(authService.currentUser(principal).username(),
                key, file.getOriginalFilename(), file.getBytes());
    }

    /** 产物下载（P23）：归属本人或 ADMIN；过期 410；RFC5987 文件名 + no-store。 */
    @GetMapping("/artifacts/{artifactId}/download")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> download(
            @RequestAttribute(JwtAuthFilter.PRINCIPAL_ATTRIBUTE) AuthPrincipal principal,
            @org.springframework.web.bind.annotation.PathVariable String artifactId) {
        String actor = authService.currentUser(principal).username();
        boolean admin = principal.roles().contains(Roles.ADMIN);
        FileProcessorService.Download download =
                fileProcessors.openForDownload(actor, admin, artifactId);
        org.springframework.core.io.FileSystemResource resource =
                new org.springframework.core.io.FileSystemResource(download.file());
        String encoded = java.net.URLEncoder.encode(download.filename(),
                java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded)
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        download.contentType()))
                .body(resource);
    }

    /** invoke 请求载荷。 */
    public record InvokeRequest(String entity) {
    }
}
