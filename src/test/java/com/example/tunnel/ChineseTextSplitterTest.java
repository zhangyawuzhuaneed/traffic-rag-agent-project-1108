package com.example.tunnel;

import com.example.tunnel.rag.ChineseTextSplitter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChineseTextSplitterTest {

    @Test
    void 空文本返回空列表() {
        assertTrue(new ChineseTextSplitter(500, 60).split("").isEmpty());
        assertTrue(new ChineseTextSplitter(500, 60).split("   \n  ").isEmpty());
        assertTrue(new ChineseTextSplitter(500, 60).split(null).isEmpty());
    }

    @Test
    void 参数非法时立刻抛错() {
        assertThrows(IllegalArgumentException.class, () -> new ChineseTextSplitter(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChineseTextSplitter(100, -1));
        assertThrows(IllegalArgumentException.class, () -> new ChineseTextSplitter(100, 100));
    }

    @Test
    void 按中文句末标点切块且不破坏句子() {
        String line = "隧道内 CO 浓度达到 70 ppm 时，应关闭入口并限制通行。";
        ChineseTextSplitter splitter = new ChineseTextSplitter(120, 20);
        String text = "# 标题\n## 一、CO 处置\n" + line.repeat(12);

        List<ChineseTextSplitter.Chunk> chunks = splitter.split(text);

        assertTrue(chunks.size() > 1, "长文本应当被切成多块");
        for (ChineseTextSplitter.Chunk c : chunks) {
            assertTrue(c.text().length() <= 120 + 20 + 10,
                    "单块长度应受 chunkSize + overlap 约束，实际 " + c.text().length());
            assertEquals("一、CO 处置", c.section(), "section 应来自二级标题");
            assertTrue(c.text().contains("。"), "切块应由完整句子组成，不应把句子拦腰截断");
        }
    }

    @Test
    void 切块索引连续且从零开始() {
        ChineseTextSplitter splitter = new ChineseTextSplitter(100, 20);
        String text = "## 小节\n" + "风机轴承温度超过 80 度应停机检修。".repeat(20);
        List<ChineseTextSplitter.Chunk> chunks = splitter.split(text);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
        }
    }

    @Test
    void 一级标题不进入正文但二级标题会被记录() {
        List<ChineseTextSplitter.Chunk> chunks =
                new ChineseTextSplitter(500, 0).split("# 隧道应急预案\n## 二、火灾处置\n确认火情后 30 秒内启动风机。\n");
        assertEquals(1, chunks.size());
        assertEquals("二、火灾处置", chunks.get(0).section());
        assertFalse(chunks.get(0).text().contains("# 隧道应急预案"));
        assertTrue(chunks.get(0).text().contains("30 秒"));
    }

    @Test
    void 重叠窗口生效() {
        ChineseTextSplitter splitter = new ChineseTextSplitter(80, 30);
        String text = "## 规则\n" + "能见度低于 50 米时关闭入口并限速 20 公里每小时。".repeat(10);
        List<ChineseTextSplitter.Chunk> chunks = splitter.split(text);
        assertTrue(chunks.size() >= 2);
        String first = chunks.get(0).text();
        // 第二块缓冲区以「第一块尾部 overlap(30) 个字符」开头，因此必然含有第一块最后 15 个字符
        String tail = first.substring(Math.max(0, first.length() - 15));
        assertTrue(chunks.get(1).text().contains(tail),
                "后一块应含前一块尾部内容（overlap），实际开头："
                        + chunks.get(1).text().substring(0, Math.min(20, chunks.get(1).text().length())));
    }
}
