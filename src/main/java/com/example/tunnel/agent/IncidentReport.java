package com.example.tunnel.agent;

import java.util.List;

/**
 * 结构化输出：让 LLM 直接吐出可落库的 Java 对象，而不是一段需要正则去抠的自然语言。
 *
 * <p>这是 AI 应用接入后端系统最关键的一步 —— 有了类型，才能做参数校验、
 * 事务落库、审计、幂等，才谈得上「AI 功能」变成「业务功能」。
 * Spring AI 的 BeanOutputConverter 会根据这个 record 生成 JSON Schema 并附到提示词里。
 *
 * <p>字段尽量用简单类型；嵌套过深或带泛型的复杂结构，模型遵从率会明显下降。
 */
public record IncidentReport(
        /** 事件编号，由模型按场景生成的可读标识，例如 INC-K12-20261108-01 */
        String incidentId,
        /** 桩号，如 K12+300 */
        String stake,
        /** 响应级别：I / II / III / IV */
        String level,
        /** 一句话事件摘要 */
        String summary,
        /** 建议处置动作，按顺序执行 */
        List<String> actions,
        /** 依据来源：知识库文件名或工具名 */
        List<String> references,
        /** 是否需要人工复核（涉及封路、疏散时必须为 true） */
        boolean needHumanConfirm
) {
}
