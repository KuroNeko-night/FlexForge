package com.flexforge.ai.model;

import com.flexforge.common.PublicApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 固定 fixture 模型（docs/09 P11：模型接口未最终确认前，开发与回归只依赖
 * fixture；fixture 优先）。脚本化两轮澄清：首轮追问字段/规则/验收标准，
 * 收到回答后产出与 prompts/v3 一一对应的确定性规格草稿+三段简报（资源文件化，
 * 回归可重复）。P15 起不注册为 Bean——由 RoutingModelPort 按有效配置路由持有。
 * P28：识别知识库助手提示词（kb-assistant 模板的「知识库参考」数据段标记），
 * 从段内解析命中条目标题，产出确定性引用式回答（助手页 fixture 演示零外部依赖）。
 */
@PublicApi
public class FixtureModelPort implements ModelPort {

    private static final String ROUND_1 = "{\"questions\":["
            + "\"需要哪些字段（名称/类型/是否必填）？\","
            + "\"有哪些业务规则（如数量非负）？\","
            + "\"验收标准是什么（至少一条可验证项）？\"]}";

    /** 助手知识段标记与条目行格式（与 flexforge-kb prompts/kb/assistant-v1.md 一一对应，
     * 模板改版措辞时必须同步，否则 fixture 助手退化为无命中口径）。 */
    static final String KB_SECTION_MARKER = "## 知识库参考（数据）";
    static final String KB_QUESTION_MARKER = "## 用户提问（数据）";
    static final String KB_NO_HIT = "（无命中条目）";
    private static final Pattern KB_ENTRY_LINE =
            Pattern.compile("^### \\[(.*)] (.+)$");

    private final String round2 = loadFixtureSpec();

    @Override
    public ModelReply complete(ModelRequest request) {
        // 助手提示词优先识别（clarify 模板不含知识库段，两路径互不干扰）
        if (request.prompt().contains(KB_SECTION_MARKER)) {
            return new ModelReply(kbAnswer(request.prompt()));
        }
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
        return "fixture-clarify-v3";
    }

    /** fixture 助手回答：无命中=明示暂无资料（不编造）；命中=逐条引用标题的演示回答。 */
    static String kbAnswer(String prompt) {
        int start = prompt.indexOf(KB_SECTION_MARKER) + KB_SECTION_MARKER.length();
        int end = prompt.indexOf(KB_QUESTION_MARKER);
        String section = end > start ? prompt.substring(start, end) : prompt.substring(start);
        if (section.contains(KB_NO_HIT)) {
            return "知识库中暂无与该问题直接相关的内容，请联系管理员在知识库页补充条目后再试。";
        }
        List<String> titles = new ArrayList<>();
        for (String line : section.split("\n")) {
            Matcher matcher = KB_ENTRY_LINE.matcher(line.strip());
            if (matcher.matches()) {
                titles.add(matcher.group(2));
            }
        }
        if (titles.isEmpty()) {
            return "知识库中暂无与该问题直接相关的内容，请联系管理员在知识库页补充条目后再试。";
        }
        StringBuilder answer = new StringBuilder("根据知识库相关条目，为你整理如下：\n");
        for (String title : titles) {
            answer.append("- 《").append(title).append("》\n");
        }
        answer.append("如需完整内容，可在知识库页查看对应条目。");
        return answer.toString();
    }

    private static String loadFixtureSpec() {
        try (InputStream in = FixtureModelPort.class.getResourceAsStream(
                "/prompts/v3/fixture-spec.json")) {
            if (in == null) {
                throw new IllegalStateException("fixture 规格资源缺失: prompts/v3/fixture-spec.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("fixture 规格资源读取失败", e);
        }
    }
}
