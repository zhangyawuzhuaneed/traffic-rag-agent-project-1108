package com.example.tunnel.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 会话记忆：保留最近 20 条消息的滑动窗口。
 *
 * <p>注意这是「进程内、按 conversationId 隔离」的记忆，服务重启即丢失，
 * 且多实例部署时各实例不共享 —— 生产要换成 Redis / JDBC 实现的
 * ChatMemoryRepository。这和传统 Web 应用里 Session 放内存还是放 Redis 是同一个权衡。
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
    }
}
