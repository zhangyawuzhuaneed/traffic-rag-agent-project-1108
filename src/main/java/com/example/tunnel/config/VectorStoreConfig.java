package com.example.tunnel.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 向量库选型说明（面试高频）：
 *
 * <p>{@link SimpleVectorStore} 是内存实现，把向量线性扫一遍算余弦相似度。
 * 它不依赖任何外部中间件，clone 下来就能跑，非常适合教学和 Demo；
 * 但它是 O(n) 全量扫描、重启需从文件恢复，生产不能这么用。
 *
 * <p>生产替换路径：把这一个 Bean 换成 PgVectorStore / RedisVectorStore / MilvusVectorStore，
 * 业务代码（RagService、KnowledgeBase）完全不用改 —— 因为它们依赖的是
 * {@code VectorStore} 接口。这就是面向接口编程在 AI 工程里的价值。
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel, RagProperties props)
            throws IOException {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        Path persisted = Path.of(props.storePath());
        if (Files.exists(persisted) && Files.size(persisted) > 0) {
            store.load(persisted.toFile());
            log.info("已从 {} 恢复向量库", persisted);
        } else {
            log.info("未发现持久化向量库，将在首次入库时创建：{}", persisted);
        }
        return store;
    }
}
