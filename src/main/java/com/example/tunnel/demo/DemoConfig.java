package com.example.tunnel.demo;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration 
public class DemoConfig {


    // c
    // public ChatMemory chatMemoryDemo() {
    //     return MessageWindowChatMemory.builder()
    //             .maxMessages(20)    // 滑动窗口：保留最近 20 条
    //             .build();
    // }
    @Bean
    public TokenTextSplitter splitter() {

        return new TokenTextSplitter();
    }
}
