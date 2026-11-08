package com.example.tunnel.support;

import com.example.tunnel.rag.KnowledgeBase;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 把「知识库是否就绪」纳入 Actuator 健康状态。
 *
 * <p><b>为什么需要它（真实踩过）：</b>
 * Spring Boot 先启动 Web 容器、再执行 {@code ApplicationRunner}。
 * 于是 {@code /actuator/health} 在向量库还是空的时候就返回 UP，
 * 此时打进来的请求只会召回 0~1 块，模型会基于残缺资料给出自信但错误的答案。
 *
 * <p>加了自定义 HealthIndicator 后，入库完成前整体健康状态是 OUT_OF_SERVICE（HTTP 503），
 * Kubernetes readinessProbe / 网关探活都会自动把流量挡住。
 * 这属于「把 AI 依赖纳入既有运维体系」，不是 AI 特有的技巧。
 */
@Component
public class KnowledgeBaseHealthIndicator implements HealthIndicator {

    private final KnowledgeBase knowledgeBase;

    public KnowledgeBaseHealthIndicator(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public Health health() {
        boolean ready = knowledgeBase.isReady();
        int chunks = knowledgeBase.indexedChunkCount();
        Health.Builder builder = ready ? Health.up() : Health.outOfService();
        return builder
                .withDetail("phase", ready ? "READY" : "INGESTING")
                .withDetail("indexedChunks", chunks)
                .build();
    }
}
