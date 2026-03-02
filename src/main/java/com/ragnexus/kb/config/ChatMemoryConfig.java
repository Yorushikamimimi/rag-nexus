package com.ragnexus.kb.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识库模块 AI 扩展配置：会话记忆等。
 */
@Configuration
public class ChatMemoryConfig {

    private static final int MEMORY_WINDOW_SIZE = 10;

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(MEMORY_WINDOW_SIZE)
                .build();
    }
}
