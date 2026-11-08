package com.example.tunnel.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 可调参数集中在这里，对应 application.yml 的 {@code app.rag.*}。
 *
 * <p>面试常问「切块大小和 top-k 怎么定」，答案不是背一个数字，
 * 而是：把它们做成可配置项 + 有一套评估集能验证改动是否变好。
 */
@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(
        String knowledgePattern,
        String storePath,
        String statePath,
        int chunkSize,
        int chunkOverlap,
        int topK,
        double similarityThreshold,
        int maxContextChars
) {
}
