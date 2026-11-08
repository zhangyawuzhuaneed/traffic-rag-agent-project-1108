package com.example.tunnel;

import com.example.tunnel.rag.IngestState;
import com.example.tunnel.rag.KnowledgeBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 同一内容指纹稳定不同内容指纹不同() {
        String a = IngestState.sha256("隧道限速 80".getBytes(StandardCharsets.UTF_8));
        String b = IngestState.sha256("隧道限速 80".getBytes(StandardCharsets.UTF_8));
        String c = IngestState.sha256("隧道限速 60".getBytes(StandardCharsets.UTF_8));
        assertEquals(a, b);
        assertFalse(a.equals(c));
        assertEquals(64, a.length());
    }

    @Test
    void 指纹未变判定为跳过变化后判定为需重建(@TempDir Path dir) throws IOException {
        Path stateFile = dir.resolve("state.json");
        IngestState state = IngestState.load(stateFile, mapper);
        String hash = IngestState.sha256("v1".getBytes(StandardCharsets.UTF_8));

        assertFalse(state.unchanged("a.md", hash));
        state.put("a.md", hash, 3);
        assertTrue(state.unchanged("a.md", hash));
        assertFalse(state.unchanged("a.md", IngestState.sha256("v2".getBytes(StandardCharsets.UTF_8))));
        assertEquals(3, state.totalChunks());
    }

    @Test
    void 旧块id可由切块数精确推导_供删除使用(@TempDir Path dir) throws IOException {
        Path stateFile = dir.resolve("state.json");
        IngestState state = IngestState.load(stateFile, mapper);
        state.put("01-emergency-plan.md", "abc", 4);

        List<String> ids = state.chunkIds("01-emergency-plan.md");
        assertEquals(4, ids.size());
        assertEquals(KnowledgeBase.chunkId("01-emergency-plan.md", 0), ids.get(0));
        assertEquals(KnowledgeBase.chunkId("01-emergency-plan.md", 3), ids.get(3));
        assertTrue(state.chunkIds("不存在.md").isEmpty());
    }

    @Test
    void 状态可持久化并跨实例恢复(@TempDir Path dir) throws IOException {
        Path stateFile = dir.resolve("nested/state.json");
        IngestState first = IngestState.load(stateFile, mapper);
        first.put("a.md", "hash-a", 2);
        first.put("b.md", "hash-b", 5);
        first.save();

        assertTrue(Files.exists(stateFile), "save 应自动创建父目录");
        IngestState restored = IngestState.load(stateFile, mapper);
        assertEquals(7, restored.totalChunks());
        assertTrue(restored.unchanged("a.md", "hash-a"));
        assertEquals(2, restored.sortedFiles().size());

        restored.remove("a.md");
        assertEquals(5, restored.totalChunks());
        assertFalse(restored.unchanged("a.md", "hash-a"));
    }
}
