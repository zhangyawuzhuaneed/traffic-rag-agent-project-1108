package com.example.tunnel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 隧道/高速智慧化 AI 应用入口。
 *
 * <p>学习路线建议按这个顺序读代码：
 * <ol>
 *   <li>{@code config/KeyGuard} —— 密钥缺失时 fail-fast，别让错误跑到几百行之后才暴露</li>
 *   <li>{@code rag/ChineseTextSplitter} —— 纯 Java，可脱离 Spring 单测，先理解"切块"</li>
 *   <li>{@code rag/KnowledgeBase} —— 加载 → 切块 → 向量化入库 → 增量指纹，离线链路</li>
 *   <li>{@code rag/RagService} —— 检索 → 拼 context → 生成 → 来源溯源，在线链路</li>
 *   <li>{@code agent/TunnelTools} + {@code agent/IncidentAssistant} —— Tool Calling 与会话记忆</li>
 *   <li>{@code api/AiController} —— 把 AI 能力包成普通 REST 接口</li>
 * </ol>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SmartTunnelAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartTunnelAiApplication.class, args);
    }
}
