package com.example.tunnel.rag;

import com.example.tunnel.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 在线问答链路：检索 → 拼 context → 生成 → 来源溯源。
 *
 * <p><b>为什么手写而不用 QuestionAnswerAdvisor：</b>
 * Spring AI 提供现成的 Advisor，一行配置就能做 RAG。但面试时如果说「我调了个 Advisor」，
 * 追问「切块怎么切 / 召回几条 / 相似度阈值多少 / 查不到怎么办 / 怎么溯源」就会答不上来。
 * 这里把六步摊开写，每一步都能指向具体代码；生产再换成 Advisor 是降本，不是能力差异。
 * 对照写法见 docs/01-架构与链路.md。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String SYSTEM_PROMPT = """
            你是「GXX 高速隧道监控中心」的知识库助手，面向运维值班人员与公众回答问题。

            硬性规则：
            1. 只能依据【参考资料】作答，不得用你自己的常识补充任何数字、阈值、时限或流程。
            2. 参考资料不足以回答时，只回复这一句：「知识库中暂未找到相关信息，请联系人值班人员核实。」
               不要编造，也不要说「根据一般经验」。
            3. 涉及数值必须原样引用资料中的写法（例如 70 ppm、80 km/h、6 个月），不要换算、四舍五入或推测。
            4. 用简体中文，先给结论，再简要说明依据；总长度控制在 200 字以内。
            """;

    private static final String USER_TEMPLATE = """
            【参考资料】
            {context}

            【问题】
            {question}

            请严格依据参考资料回答。
            """;

    public record Citation(String source, String section, double score, String excerpt) {
    }

    public record Answer(String question, String answer, List<Citation> citations,
                         int retrieved, boolean refused) {
    }

    private final ChatClient chatClient;
    private final SimpleVectorStore store;
    private final RagProperties props;
    private final KnowledgeBase knowledgeBase;

    public RagService(ChatClient.Builder builder, SimpleVectorStore store,
                      RagProperties props, KnowledgeBase knowledgeBase) {
        this.chatClient = builder.build();
        this.store = store;
        this.props = props;
        this.knowledgeBase = knowledgeBase;
    }

    public Answer ask(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        // 二次兜底：即使请求绕过健康检查（比如直接打到 Pod），也不能用空库编答案
        if (!knowledgeBase.isReady()) {
            throw new IllegalStateException("知识库正在初始化，请稍后重试");
        }

        // 1) 检索：Spring AI 会自动把 query 走 EmbeddingModel 向量化，再算余弦相似度
        List<Document> docs = store.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(props.topK())
                .similarityThreshold(props.similarityThreshold())
                .build());

        // 2) 组装 context，并给每块带上来源标记，便于 LLM 引用也便于我们做溯源
        String context = buildContext(docs);
        log.debug("召回 {} 块，context {} 字符", docs.size(), context.length());

        // 3) 生成
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(u -> u.text(USER_TEMPLATE)
                        .param("context", context)
                        .param("question", question))
                .call()
                .content();
        if (answer == null) {
            answer = "";
        }

        // 4) 溯源 + 拒答识别
        List<Citation> citations = docs.stream().map(this::toCitation).toList();
        boolean refused = answer.contains("暂未找到相关信息") || answer.isBlank();
        return new Answer(question, answer.trim(), citations, docs.size(), refused);
    }

    private String buildContext(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (Document d : docs) {
            String source = String.valueOf(d.getMetadata().getOrDefault("source", "unknown"));
            String section = String.valueOf(d.getMetadata().getOrDefault("section", "概述"));
            String block = "[来源: " + source + " / " + section + "]\n" + d.getText() + "\n\n";
            if (used + block.length() > props.maxContextChars()) {
                break;
            }
            sb.append(block);
            used += block.length();
        }
        return sb.isEmpty() ? "（无参考资料）" : sb.toString();
    }

    private Citation toCitation(Document d) {
        String excerpt = d.getText() == null ? "" : d.getText();
        if (excerpt.length() > 120) {
            excerpt = excerpt.substring(0, 120) + "…";
        }
        Double score = d.getScore();
        return new Citation(
                String.valueOf(d.getMetadata().getOrDefault("source", "unknown")),
                String.valueOf(d.getMetadata().getOrDefault("section", "概述")),
                score == null ? 0.0 : score,
                excerpt);
    }

    /** 只暴露检索结果，方便写单元测试与调试「为什么召回不对」。 */
    public List<Citation> search(String question) {
        List<Citation> result = new ArrayList<>();
        for (Document d : store.similaritySearch(SearchRequest.builder()
                .query(question).topK(props.topK())
                .similarityThreshold(props.similarityThreshold()).build())) {
            result.add(toCitation(d));
        }
        return result;
    }
}
