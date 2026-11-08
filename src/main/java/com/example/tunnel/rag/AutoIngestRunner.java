package com.example.tunnel.rag;

import com.example.tunnel.rag.KnowledgeBase.IngestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时自动做一次增量入库，做到「配好 Key 就能直接提问」。
 *
 * <p>顺序必须在 KeyGuard 之后：Key 没配时先报清晰的凭证错误，而不是先撞上一堆 401。
 * CI / 单元测试不会启动完整 Spring 上下文，因此不会产生真实 API 调用；
 * 需要关闭时设 {@code app.rag.auto-ingest=false}。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "app.rag.auto-ingest", havingValue = "true", matchIfMissing = true)
public class AutoIngestRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AutoIngestRunner.class);

    private final KnowledgeBase knowledgeBase;

    public AutoIngestRunner(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        IngestResult result = knowledgeBase.ingest(false);
        log.info("知识库就绪：处理 {} 个文件，跳过 {} 个，清理 {} 个，共 {} 块",
                result.processed(), result.skipped(), result.removed(), result.totalChunks());
        if (result.totalChunks() == 0) {
            log.warn("向量库为空，请检查 src/main/resources/knowledge/ 下是否有 .md 文件");
        }
    }
}
