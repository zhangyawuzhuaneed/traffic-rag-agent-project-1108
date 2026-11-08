package com.example.tunnel.rag;

import com.example.tunnel.config.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 离线入库链路：加载 → 切块 → （由 VectorStore 内部调用 EmbeddingModel）→ 写入向量库 → 落盘。
 *
 * <p>这是整个 RAG 最"后端"的一段：本质上就是一个带幂等和增量判断的 ETL。
 * 把它当批量导入任务去理解，比当成"AI 魔法"更容易上手。
 */
@Service
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    public record IngestResult(int processed, int skipped, int removed, int totalChunks,
                               List<String> details) {
    }

    private final SimpleVectorStore store;
    private final RagProperties props;
    private final ObjectMapper mapper;
    private final ChineseTextSplitter splitter;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    /** 入库完成标记。Spring Boot 的 Web 容器早于 ApplicationRunner 就绪，需要显式暴露这个状态。 */
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public KnowledgeBase(SimpleVectorStore store, RagProperties props, ObjectMapper mapper) {
        this.store = store;
        this.props = props;
        this.mapper = mapper;
        this.splitter = new ChineseTextSplitter(props.chunkSize(), props.chunkOverlap());
    }

    public static String chunkId(String file, int index) {
        return file + "#" + index;
    }

    /**
     * @param force true 时忽略指纹强制重建。注意：即便 force，也必须先删旧块再写新块，
     *              否则集合里会留下重复内容（这是 Python 版项目踩过的真实 bug）。
     */
    public synchronized IngestResult ingest(boolean force) throws IOException {
        IngestState state = IngestState.load(Path.of(props.statePath()), mapper);
        Resource[] resources = resolver.getResources(props.knowledgePattern());
        Arrays.sort(resources, Comparator.comparing(r -> r.getFilename() == null ? "" : r.getFilename()));

        List<String> details = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        int processed = 0;
        int skipped = 0;

        for (Resource resource : resources) {
            String file = resource.getFilename();
            if (file == null || !file.endsWith(".md")) {
                continue;
            }
            present.add(file);
            byte[] bytes = resource.getInputStream().readAllBytes();
            String fingerprint = IngestState.sha256(bytes);

            if (!force && state.unchanged(file, fingerprint)) {
                skipped++;
                details.add("skip  " + file + " (指纹未变化)");
                continue;
            }

            // 关键顺序：先删旧块，再写新块
            List<String> oldIds = state.chunkIds(file);
            if (!oldIds.isEmpty()) {
                store.delete(oldIds);
            }

            List<ChineseTextSplitter.Chunk> chunks =
                    splitter.split(new String(bytes, StandardCharsets.UTF_8));
            List<Document> documents = chunks.stream()
                    .map(c -> Document.builder()
                            .id(chunkId(file, c.index()))
                            .text(c.text())
                            .metadata(metadata(file, c))
                            .build())
                    .toList();

            if (!documents.isEmpty()) {
                store.add(documents); // 内部会调用 EmbeddingModel 批量向量化
            }
            state.put(file, fingerprint, chunks.size());
            processed++;
            details.add("index " + file + " -> " + chunks.size() + " 块");
            log.info("入库 {}：{} 块（force={}）", file, chunks.size(), force);
        }

        // 清理已从 classpath 消失的文档，避免知识库残留旧内容
        int removed = 0;
        for (String stale : state.files()) {
            if (!present.contains(stale)) {
                List<String> ids = state.chunkIds(stale);
                if (!ids.isEmpty()) {
                    store.delete(ids);
                }
                state.remove(stale);
                removed++;
                details.add("remove " + stale + " (源文件已不存在)");
            }
        }

        state.save();
        Path storePath = Path.of(props.storePath());
        Path parent = storePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        store.save(storePath.toFile());
        ready.set(true);

        return new IngestResult(processed, skipped, removed, state.totalChunks(), details);
    }

    /**
     * 是否已完成至少一次入库。健康检查与 RAG 接口都依赖它，
     * 避免「Web 已能访问、向量库还是空的」时返回似是而非的答案。
     */
    public boolean isReady() {
        // 关闭自动入库但磁盘上已有状态时，也视为就绪
        return ready.get() || indexedChunkCount() > 0;
    }

    public int indexedChunkCount() {
        try {
            return IngestState.load(Path.of(props.statePath()), mapper).totalChunks();
        } catch (IOException e) {
            return 0;
        }
    }

    private Map<String, Object> metadata(String file, ChineseTextSplitter.Chunk chunk) {
        Map<String, Object> m = new HashMap<>();
        // source 同时被增量删除逻辑依赖，不要随意改字段名
        m.put("source", file);
        m.put("section", chunk.section());
        m.put("chunk", chunk.index());
        m.put("scene", "tunnel-highway");
        return m;
    }
}
