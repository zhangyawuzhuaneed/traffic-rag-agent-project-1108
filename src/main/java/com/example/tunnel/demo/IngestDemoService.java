package com.example.tunnel.demo;


import jakarta.annotation.PostConstruct;
import org.springframework.core.io.FileSystemResource;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class IngestDemoService {

    // 待入库的本地文档路径
    private static final Path COURSE_DOC =
            Paths.get("D:\\ExamSoft\\ollama\\.vscode\\agent\\agent1103\\smart-tunnel-ai\\src\\main\\java\\com\\example\\tunnel\\demo\\demo-rag.txt");

    private final VectorStore vectorStore;
    private final TokenTextSplitter splitter;

    public IngestDemoService(VectorStore vectorStore, TokenTextSplitter splitter) {
        this.vectorStore = vectorStore;
        this.splitter = splitter;
    }

    // 服务启动后自动执行文档入库
    @PostConstruct
    public void init() throws IOException {
        ingest(COURSE_DOC);
    }

    /**
     * ETL 核心流程：读取文档 → 文本切块 → 向量化 → 存入向量库
     */
    public void ingest(Path file) throws IOException {
        // 1. 读取文档：Tika 自动提取文件文本内容，支持 txt/pdf/word/excel 等多种格式
        List<Document> documents = new TikaDocumentReader(
                new FileSystemResource(file.toFile()))
                .read();

        // 2. 文本切块 + 3. 向量化入库
        // splitter.apply 将长文本切分为多个语义块；vectorStore.add 自动调用 Embedding 模型转向量并持久化
        vectorStore.add(splitter.apply(documents));
    }
}