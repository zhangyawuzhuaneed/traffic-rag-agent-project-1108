package com.example.tunnel.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 增量入库状态：文件 → (sha256 指纹, 切块数)。
 *
 * <p>解决的问题：每次启动都重新 embedding 全部文档，既费钱又慢；
 * 而文件变化后如果只追加不删旧块，新旧内容会同时被召回（这是真实踩过的坑）。
 * 这里用 sha256 判断文件是否变化，用 chunkCount 精确推导出需要删除的旧 Document id。
 *
 * <p>注意 key 用文件名而非完整路径；如果以后知识库出现子目录同名文件，
 * 应改成相对路径作为 key（已记入 docs/04-已知问题与演进.md）。
 */
public final class IngestState {

    public record Entry(String sha256, int chunkCount) {
    }

    private final Path path;
    private final ObjectMapper mapper;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private IngestState(Path path, ObjectMapper mapper) {
        this.path = path;
        this.mapper = mapper;
    }

    public static IngestState load(Path path, ObjectMapper mapper) throws IOException {
        IngestState state = new IngestState(path, mapper);
        if (Files.exists(path) && Files.size(path) > 0) {
            Map<String, Entry> loaded = mapper.readValue(
                    path.toFile(), new TypeReference<>() {
                    });
            state.entries.putAll(loaded);
        }
        return state;
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    public boolean unchanged(String file, String fingerprint) {
        Entry e = entries.get(file);
        return e != null && e.sha256().equals(fingerprint);
    }

    /** 由旧切块数推导出旧 Document id，供 VectorStore.delete 精确删除。 */
    public List<String> chunkIds(String file) {
        Entry e = entries.get(file);
        if (e == null || e.chunkCount() <= 0) {
            return List.of();
        }
        return IntStream.range(0, e.chunkCount())
                .mapToObj(i -> KnowledgeBase.chunkId(file, i))
                .toList();
    }

    public void put(String file, String fingerprint, int chunkCount) {
        entries.put(file, new Entry(fingerprint, chunkCount));
    }

    public void remove(String file) {
        entries.remove(file);
    }

    public Set<String> files() {
        return Set.copyOf(entries.keySet());
    }

    public int totalChunks() {
        return entries.values().stream().mapToInt(Entry::chunkCount).sum();
    }

    public void save() throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries),
                StandardCharsets.UTF_8);
    }

    /** 当前已登记的文件列表（稳定顺序，便于日志与测试断言）。 */
    public List<String> sortedFiles() {
        return new ArrayList<>(entries.keySet());
    }
}
