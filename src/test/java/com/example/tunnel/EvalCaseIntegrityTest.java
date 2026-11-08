package com.example.tunnel;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>整个项目最容易被忽略、但面试最值得讲的一个测试。</b>
 *
 * <p>评估集是 AI 项目的「单元测试用例」，但它很容易变成假用例：
 * 人凭印象写了一个标准答案，知识库里其实根本没这句话，
 * 于是模型答不对被误判成「模型不行」，而不是「评估集写错了」。
 *
 * <p>这个测试强制：每条正向用例的期望关键词，必须能在它声明的源文档中逐字找到，
 * 且覆盖全部 5 份知识库、包含拒答用例。相当于「给测试本身写测试」。
 */
class EvalCaseIntegrityTest {

    public record EvalCase(String id, String question, List<String> expectKeywords,
                           String source, boolean mustRefuse, String note) {
    }

    private static final Path CASES = Path.of("src/test/resources/eval-cases.json");
    private static final Path KNOWLEDGE = Path.of("src/main/resources/knowledge");

    private List<EvalCase> loadCases() throws IOException {
        return new ObjectMapper().readValue(
                Files.readString(CASES, StandardCharsets.UTF_8),
                new TypeReference<>() {
                });
    }

    @Test
    void 评估集至少20条且id唯一() throws IOException {
        List<EvalCase> cases = loadCases();
        assertTrue(cases.size() >= 20, "评估集应至少 20 条，实际 " + cases.size());
        Set<String> ids = new HashSet<>();
        for (EvalCase c : cases) {
            assertTrue(c.id() != null && !c.id().isBlank(), "id 不能为空");
            assertTrue(ids.add(c.id()), "id 重复：" + c.id());
            assertTrue(c.question() != null && !c.question().isBlank(), c.id() + " 问题为空");
        }
    }

    @Test
    void 期望关键词必须真实存在于声明的源文档中() throws IOException {
        List<EvalCase> cases = loadCases();
        for (EvalCase c : cases) {
            Path source = KNOWLEDGE.resolve(c.source());
            assertTrue(Files.exists(source), c.id() + " 声明的源文档不存在：" + c.source());
            if (c.mustRefuse()) {
                continue;
            }
            assertFalse(c.expectKeywords().isEmpty(), c.id() + " 正向用例必须有期望关键词");
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String keyword : c.expectKeywords()) {
                assertTrue(text.contains(keyword),
                        c.id() + " 的期望关键词「" + keyword + "」在 " + c.source()
                                + " 中找不到 —— 这是编造的评估集，请修正");
            }
        }
    }

    @Test
    void 五份知识库都被覆盖且包含拒答用例() throws IOException {
        List<EvalCase> cases = loadCases();
        Set<String> sources = new HashSet<>();
        long refusal = cases.stream().filter(EvalCase::mustRefuse).count();
        cases.forEach(c -> sources.add(c.source()));
        assertEquals(5, sources.size(), "评估集应覆盖全部 5 份知识库，实际 " + sources);
        assertTrue(refusal >= 3, "至少 3 条拒答用例，用于验证防幻觉，实际 " + refusal);
    }

    @Test
    void 知识库文件均为UTF8且非空() throws IOException {
        try (var stream = Files.list(KNOWLEDGE)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".md")).sorted().toList();
            assertEquals(5, files.size(), "知识库应有 5 份 Markdown");
            for (Path p : files) {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                assertTrue(text.length() > 300, p.getFileName() + " 内容过短，可能被截断");
                assertTrue(text.contains("# "), p.getFileName() + " 缺少标题");
            }
        }
    }
}
