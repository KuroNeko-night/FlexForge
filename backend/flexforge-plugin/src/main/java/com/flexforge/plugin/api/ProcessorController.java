package com.flexforge.plugin.api;

import com.flexforge.auth.AuthPrincipal;
import com.flexforge.auth.Roles;
import com.flexforge.auth.api.JwtAuthFilter;
import com.flexforge.auth.api.RequireRole;
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

    public ProcessorController(ProcessorService processors,
                               com.flexforge.auth.core.AuthService authService) {
        this.processors = processors;
        this.authService = authService;
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

    /** invoke 请求载荷。 */
    public record InvokeRequest(String entity) {
    }
}
