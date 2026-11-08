package com.example.tunnel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把「不要提交密钥」从口头约定变成机器强制。
 *
 * <p>和 CI 里的 gitleaks 是互补关系：这个测试在开发者本机跑，几毫秒就有反馈；
 * gitleaks 扫全历史，防止有人绕过本地检查。
 */
class SecurityGuardTest {

    /** 拼接写法，避免测试自己命中自己扫描的字面量（Python 版项目踩过这个自指坑）。 */
    private static final String REAL_KEY_PREFIX = "fc35" + "fac6";

    @Test
    void 所有应入库的文本文件都不含真实Key() throws IOException {
        Path root = Path.of(".");
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> !isIgnored(p))
                    .filter(SecurityGuardTest::isTextFile)
                    .toList();
        }
        assertFalse(files.isEmpty(), "扫描到 0 个文件，检查扫描逻辑");
        for (Path p : files) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            assertFalse(text.contains(REAL_KEY_PREFIX),
                    "发现疑似真实密钥：" + p + " —— 请立即吊销并改为环境变量注入");
        }
    }

    @Test
    void 忽略规则覆盖敏感文件() throws IOException {
        String gitignore = Files.readString(Path.of(".gitignore"), StandardCharsets.UTF_8);
        for (String rule : List.of(".env", "application-local.yml", "data/vector-store.json",
                "data/ingest-state.json", "target/", ".workbuddy/")) {
            assertTrue(gitignore.contains(rule), ".gitignore 缺少规则：" + rule);
        }
    }

    @Test
    void 配置从环境变量读取而非硬编码() throws IOException {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"),
                StandardCharsets.UTF_8);
        assertTrue(yml.contains("${ZHIPU_API_KEY"), "api-key 必须使用环境变量占位符");
        assertTrue(yml.contains("completions-path"), "智谱端点需要覆盖默认 /v1 路径");
        assertTrue(yml.contains("embeddings-path"), "智谱端点需要覆盖默认 /v1 路径");
        assertFalse(yml.contains(REAL_KEY_PREFIX));
    }

    @Test
    void 示例文件只有占位符() throws IOException {
        String example = Files.readString(Path.of(".env.example"), StandardCharsets.UTF_8);
        assertTrue(example.contains("your_zhipu_api_key_here"));
        assertFalse(example.contains(REAL_KEY_PREFIX));
    }

    private static boolean isIgnored(Path p) {
        String s = p.toString().replace('\\', '/');
        for (String part : new String[]{"/target/", "/.git/", "/data/", "/.workbuddy/", "/.mvn/"}) {
            if (s.contains(part)) {
                return true;
            }
        }
        return s.endsWith("/.env");
    }

    private static boolean isTextFile(Path p) {
        String n = p.getFileName().toString();
        Set<String> ext = Set.of(".java", ".yml", ".yaml", ".md", ".xml", ".json", ".properties", ".txt");
        return ext.stream().anyMatch(n::endsWith) || n.equals(".gitignore") || n.equals(".env.example");
    }
}
