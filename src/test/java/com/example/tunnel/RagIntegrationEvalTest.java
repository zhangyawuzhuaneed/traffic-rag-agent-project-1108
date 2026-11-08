package com.example.tunnel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端评估：真实启动 Spring Boot、真实调 LLM、按 20 条评估集算命中率。
 *
 * <p>默认跳过 —— 单元测试不应该依赖外部服务和账单。显式开启：
 * <pre>
 * set RUN_INTEGRATION=true &amp;&amp; set ZHIPU_API_KEY=你的key
 * .\mvnw.cmd test -Dtest=RagIntegrationEvalTest
 * </pre>
 *
 * <p><b>面试讲法：</b>「LLM 输出不确定，所以我没有写 assertEquals，
 * 而是把验收标准降级成关键词命中率 + 拒答率，先建立可比较的基线；
 * 改切块大小 / top-k / 提示词之后再跑一遍，用数字判断有没有变好。」
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION", matches = "true")
class RagIntegrationEvalTest {

    public record EvalCase(String id, String question, List<String> expectKeywords,
                           String source, boolean mustRefuse, String note) {
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 跑完20条评估集并输出命中率基线() throws IOException {
        List<EvalCase> cases = mapper.readValue(
                Files.readString(Path.of("src/test/resources/eval-cases.json"), StandardCharsets.UTF_8),
                new TypeReference<>() {
                });

        int passed = 0;
        List<String> failures = new ArrayList<>();
        for (EvalCase c : cases) {
            String body = restTemplate.postForObject(
                    "/api/rag/ask", Map.of("question", c.question()), String.class);
            JsonNode node = mapper.readTree(body == null ? "{}" : body);
            String answer = node.path("answer").asText("");
            boolean refused = node.path("refused").asBoolean(false);

            if (c.mustRefuse()) {
                boolean ok = refused || answer.contains("未找到") || answer.contains("暂未");
                if (ok) {
                    passed++;
                } else {
                    failures.add(c.id() + " 应拒答但答了：" + abbreviate(answer));
                }
                continue;
            }

            long hits = c.expectKeywords().stream().filter(answer::contains).count();
            boolean ok = !refused && hits > 0;
            if (ok) {
                passed++;
            } else {
                failures.add(c.id() + " 命中 0 个关键词（期望任一：" + c.expectKeywords()
                        + "），实际：" + abbreviate(answer));
            }
        }

        System.out.printf("%n===== RAG 评估基线：%d/%d 通过（%.0f%%）=====%n",
                passed, cases.size(), 100.0 * passed / cases.size());
        failures.forEach(f -> System.out.println("  FAIL " + f));

        // 允许少量波动，但整体命中率不能塌掉；想更严格可把阈值提到 0.9
        assertTrue((double) passed / cases.size() >= 0.75,
                "整体命中率低于 75%，请检查检索链路或知识库；失败明细：\n" + String.join("\n", failures));
    }

    private String abbreviate(String s) {
        return s.length() > 100 ? s.substring(0, 100) + "…" : s;
    }
}
