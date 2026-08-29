package com.flexforge.ai.model;

import com.flexforge.common.PublicApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 固定 fixture 模型（docs/09 P11：模型接口未最终确认前，开发与回归只依赖
 * fixture；fixture 优先）。脚本化两轮澄清：首轮追问字段/规则/验收标准，
 * 收到回答后产出与 prompts/v1 一一对应的确定性规格草稿（资源文件化，
 * 回归可重复）。默认装配（未显式选择 http 时）。
 */
@PublicApi
@Component
@ConditionalOnProperty(value = "flexforge.ai.provider",
        havingValue = "fixture", matchIfMissing = true)
public class FixtureModelPort implements ModelPort {

    private static final String ROUND_1 = "{\"questions\":["
            + "\"需要哪些字段（名称/类型/是否必填）？\","
            + "\"有哪些业务规则（如数量非负）？\","
            + "\"验收标准是什么（至少一条可验证项）？\"]}";

    private final String round2 = loadFixtureSpec();

    @Override
    public ModelReply complete(ModelRequest request) {
        // 确定性脚本：提示词带非空用户回答（第二轮）则产出规格，否则追问。
        // 轮次判定依赖 clarify.md 模板中"## 用户回答（数据）"段标记——模板改版
        // 措辞时必须同步此判定，否则 fixture 会永远停在追问轮。
        String marker = "## 用户回答（数据）";
        int idx = request.prompt().indexOf(marker);
        boolean hasAnswer = idx >= 0
                && !request.prompt().substring(idx + marker.length()).strip().equals("（无）");
        return new ModelReply(hasAnswer ? round2 : ROUND_1);
    }

    @Override
    public String name() {
        return "fixture-clarify-v1";
    }

    private static String loadFixtureSpec() {
        try (InputStream in = FixtureModelPort.class.getResourceAsStream(
                "/prompts/v1/fixture-spec.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture 规格资源缺失: prompts/v1/fixture-spec.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 规格资源读取失败", e);
        }
    }
}
