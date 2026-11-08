package com.example.tunnel.api;

import com.example.tunnel.agent.IncidentAssistant;
import com.example.tunnel.agent.IncidentReport;
import com.example.tunnel.rag.KnowledgeBase;
import com.example.tunnel.rag.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * AI 能力对外暴露成普通 REST 接口。
 *
 * <p>刻意保持「Controller 很薄」：不做提示词拼接、不写业务逻辑，全部下沉到 Service。
 * 对你熟悉的后端分层来说，这里没有任何新东西 —— AI 能力只是另一种被调用的依赖，
 * 照样要做参数校验、超时控制、异常映射和审计日志。
 */
@RestController
@RequestMapping("/api")
public class AiController {

    public record AskRequest(String question) {
    }

    public record ChatRequest(String conversationId, String message) {
    }

    public record AssessRequest(String conversationId, String scenario) {
    }

    public record ChatResponse(String conversationId, String reply) {
    }

    public record StatusResponse(String model, String vectorStore, int indexedChunks,
                                 String knowledgeBase) {
    }

    private final RagService ragService;
    private final IncidentAssistant incidentAssistant;
    private final KnowledgeBase knowledgeBase;
    private final String model;

    public AiController(RagService ragService,
                        IncidentAssistant incidentAssistant,
                        KnowledgeBase knowledgeBase,
                        @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.ragService = ragService;
        this.incidentAssistant = incidentAssistant;
        this.knowledgeBase = knowledgeBase;
        this.model = model;
    }

    /** 纯 RAG 问答：固定「先检索、后生成」，返回答案 + 来源引用。 */
    @PostMapping("/rag/ask")
    public RagService.Answer ask(@RequestBody AskRequest request) {
        return ragService.ask(request.question());
    }

    /** 只看检索结果，用来调「为什么召回不对」，不消耗生成 token。 */
    @GetMapping("/rag/search")
    public Object search(@RequestParam String question) {
        return Map.of("question", question, "citations", ragService.search(question));
    }

    /** Agent 多轮对话：同一个 conversationId 内保留上下文。 */
    @PostMapping("/agent/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? "default" : request.conversationId();
        return new ChatResponse(conversationId,
                incidentAssistant.chat(conversationId, request.message()));
    }

    /** Agent 场景研判：模型自主调工具，返回可直接落库的结构化工单。 */
    @PostMapping("/agent/assess")
    public IncidentReport assess(@RequestBody AssessRequest request) {
        String conversationId = (request.conversationId() == null || request.conversationId().isBlank())
                ? "assess" : request.conversationId();
        return incidentAssistant.assess(conversationId, request.scenario());
    }

    @DeleteMapping("/agent/chat")
    public Map<String, String> clear(@RequestParam(defaultValue = "default") String conversationId) {
        incidentAssistant.clear(conversationId);
        return Map.of("cleared", conversationId);
    }

    /** 改了知识库文档后手动触发重新入库（默认增量，force=true 全量重建）。 */
    @PostMapping("/admin/reindex")
    public KnowledgeBase.IngestResult reindex(
            @RequestParam(defaultValue = "false") boolean force) throws Exception {
        return knowledgeBase.ingest(force);
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(model, "SimpleVectorStore(内存 + 文件持久化)",
                knowledgeBase.indexedChunkCount(), "src/main/resources/knowledge/*.md");
    }
}
