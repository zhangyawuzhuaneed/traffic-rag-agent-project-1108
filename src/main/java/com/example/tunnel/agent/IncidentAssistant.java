package com.example.tunnel.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * 事件处置助手：把 Tool Calling、会话记忆、结构化输出三件事串在一起。
 *
 * <p>与普通 RAG 的本质区别：RAG 是<b>固定流水线</b>（必定先检索再生成），
 * Agent 是<b>模型自主决策</b>（要不要调工具、调哪个、调几次，由 LLM 决定）。
 * 代价是延迟和不确定性上升，所以简单问答仍然应该走 RagService，别什么都塞给 Agent。
 */
@Service
public class IncidentAssistant {

    private static final Logger log = LoggerFactory.getLogger(IncidentAssistant.class);

    private static final String SYSTEM_PROMPT = """
            你是 GXX 高速 K10+000 至 K15+500 路段的监控中心事件处置助手。

            工作方式：
            1. 涉及「当前 / 实时 / 现在」的数据，必须调用工具查询，禁止凭常识编造数值。
            2. 涉及预案、阈值、限速规则等静态规定时，在依据中注明对应的知识库文件名。
            3. 处置动作要按先后顺序给出，并优先保证人员安全。
            4. 凡涉及封闭隧道、疏散人员、危化品处置，needHumanConfirm 必须为 true —— AI 只给建议，不代替人下发指令。
            5. 输出必须是严格 JSON，不要包含 Markdown 代码块、注释或额外说明。
            """;

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final TunnelTools tools;

    public IncidentAssistant(ChatClient.Builder builder, ChatMemory chatMemory, TunnelTools tools) {
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
        this.chatMemory = chatMemory;
        this.tools = tools;
    }

    /**
     * 场景研判：一次性返回可落库的结构化事件工单。
     * 模型可能自主连续调用多个工具（先查实时 CO，再查响应级别，再查当前管制）。
     */
    public IncidentReport assess(String conversationId, String scenario) {
        log.info("[AGENT] assess conversation={}", conversationId);
        return chatClient.prompt()
                .user(u -> u.text("""
                        请研判以下事件并生成结构化处置工单：

                        {scenario}

                        要求：先调用必要工具核实实时数据，再给出分级、动作与依据。
                        """).param("scenario", scenario))
                .tools(tools)
                .advisors(spec -> spec
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .entity(IncidentReport.class);
    }

    /** 带会话记忆的自由追问：同一个 conversationId 内模型能记住上文。 */
    public String chat(String conversationId, String message) {
        String reply = chatClient.prompt()
                .user(message)
                .tools(tools)
                .advisors(spec -> spec
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content(); 
        return reply == null ? "" : reply.trim();
    }

    /** 清空某个会话，对应前端「新开对话」。 */
    public void clear(String conversationId) {
        chatMemory.clear(conversationId);
    }
}
