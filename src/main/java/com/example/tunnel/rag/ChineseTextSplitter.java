package com.example.tunnel.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 面向中文 Markdown 的切块器。刻意不依赖 Spring / Spring AI，方便纯 JUnit 单测。
 *
 * <p><b>为什么不能照搬英文那套「按空格 / token 切」：</b>
 * 中文没有词边界，按固定字符数硬切会把「CO 浓度 70 ppm 关闭入口」拦腰截断，
 * 检索时召回半句话，LLM 就会漏掉关键数值。所以这里先按中文句末标点分句，
 * 再把整句累积到接近 chunkSize，尽量不破坏语义单元。
 *
 * <p>另外会识别 {@code ## } 二级标题并记录为 section，随块写入 metadata，
 * 这样回答里可以给出「依据：03-speed-rules.md / 二、恶劣天气限速」而不是一句干巴巴的答案。
 */
public class ChineseTextSplitter {

    /** 一个切块，index 在同一文档内单调递增，同时用作向量库 Document 的 id 后缀。 */
    public record Chunk(int index, String section, String text) {
    }

    private final int chunkSize;
    private final int overlap;

    public ChineseTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须 > 0");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 必须 >= 0 且 < chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public List<Chunk> split(String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return chunks;
        }

        String section = "概述";
        // 记录「当前缓冲区起始时」的 section，避免一个块跨两个小节时归属错乱
        String sectionAtStart = section;
        StringBuilder buffer = new StringBuilder();
        int index = 0;

        for (String rawLine : markdown.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("# ")) {
                continue; // 一级标题是文档名，不进正文
            }
            if (line.startsWith("## ")) {
                section = line.substring(3).trim();
                if (buffer.isEmpty()) {
                    sectionAtStart = section;
                }
                continue;
            }

            if (buffer.isEmpty()) {
                sectionAtStart = section;
            }

            for (String unit : sentenceUnits(line)) {
                if (buffer.length() + unit.length() > chunkSize && !buffer.isEmpty()) {
                    String text = buffer.toString().trim();
                    if (!text.isEmpty()) {
                        chunks.add(new Chunk(index++, sectionAtStart, text));
                    }
                    buffer = new StringBuilder(tail(text, overlap));
                    sectionAtStart = section;
                }
                buffer.append(unit);
            }
            buffer.append('\n');
        }

        String rest = buffer.toString().trim();
        if (!rest.isEmpty()) {
            chunks.add(new Chunk(index, sectionAtStart, rest));
        }
        return chunks;
    }

    /** 按中文/英文句末标点分句，保留标点本身。 */
    private List<String> sentenceUnits(String line) {
        return Arrays.stream(line.split("(?<=[。！？；!?;])"))
                .filter(s -> !s.isBlank())
                .toList();
    }

    /** 取尾部 n 个字符作为下一块的重叠，模拟滑动窗口，降低跨块边界丢信息的概率。 */
    private String tail(String text, int n) {
        if (n <= 0 || text.isEmpty()) {
            return "";
        }
        return text.substring(Math.max(0, text.length() - n));
    }
}
