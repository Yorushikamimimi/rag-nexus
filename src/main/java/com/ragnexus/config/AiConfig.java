package com.ragnexus.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient 显式注册：组合 ChatModel（由 spring-ai-starter-model-openai 自动配置，
 * 适配阿里云 DashScope 等 OpenAI 兼容端点）+ ChatMemory（多轮对话）。
 * <p>ChatModel / EmbeddingModel 不再手搓，避免与 starter 自动配置冲突或冗余。
 * base-url / api-key / model 全部走 application-*.yml 的 spring.ai.openai.* 属性。
 */
@Configuration
public class AiConfig {

    @Bean
    @ConditionalOnMissingBean(ChatClient.class)
    public ChatClient chatClient(ChatModel chatModel, ChatMemory chatMemory) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
