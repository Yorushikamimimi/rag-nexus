package com.ragnexus.kb.service;

import com.ragnexus.kb.domain.dto.KbChatRequest;
import com.ragnexus.kb.domain.dto.KbChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 对话服务：相似度检索 + System Prompt 组装 + ChatClient Fluent API 调用，带 Fallback。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private static final String METADATA_DOC_NAME = "docName";
    private static final String FALLBACK_ANSWER = "当前无法连接大模型或知识库检索异常，请稍后重试。";

    private static final String DEFAULT_SESSION_ID = "default";

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    /**
     * 将 query 向量化后做相似度检索，Top-K 切片拼进 System Prompt，再调用 LLM 生成回答。
     * 异常时返回兜底文案，不抛异常。
     */
    public KbChatResponse chat(KbChatRequest request) {
        int topK = request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : 3;
        String query = request.getQuery();
        double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;

        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build();
            List<Document> docs = vectorStore.similaritySearch(searchRequest);

            String systemPrompt = buildRagSystemPrompt(docs);
            List<String> citations = docs.stream()
                    .map(d -> (String) d.getMetadata().get(METADATA_DOC_NAME))
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank()
                    ? request.getSessionId()
                    : DEFAULT_SESSION_ID;
            ChatOptions options = ChatOptions.builder()
                    .temperature(temperature)
                    .build();
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(query)
                    .options(options)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();

            return KbChatResponse.builder()
                    .answer(answer != null ? answer : FALLBACK_ANSWER)
                    .citations(citations)
                    .build();
        } catch (Exception e) {
            log.error("RagChatService.chat failed: query={}", query, e);
            return KbChatResponse.builder()
                    .answer(FALLBACK_ANSWER)
                    .citations(Collections.emptyList())
                    .build();
        }
    }

    /**
     * 流式 RAG 对话：与 chat() 相同的检索、System Prompt、多轮记忆逻辑，返回 Flux 供 SSE 推送。
     * 异常时返回兜底文案的 Flux。
     */
    public Flux<String> streamChat(KbChatRequest request) {
        int topK = request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : 3;
        String query = request.getQuery();
        double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;

        try {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build();
            List<Document> docs = vectorStore.similaritySearch(searchRequest);

            String systemPrompt = buildRagSystemPrompt(docs);
            String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank()
                    ? request.getSessionId()
                    : DEFAULT_SESSION_ID;
            ChatOptions options = ChatOptions.builder()
                    .temperature(temperature)
                    .build();

            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(query)
                    .options(options)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .content();
        } catch (Exception e) {
            log.error("RagChatService.streamChat failed: query={}", query, e);
            return Flux.just(FALLBACK_ANSWER);
        }
    }

    private String buildRagSystemPrompt(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "你是一个知识库助手。当前未检索到相关文档，请基于常识回答。";
        }
        StringBuilder context = new StringBuilder("你是一个基于知识库的问答助手。请严格依据以下检索到的文档片段回答用户问题，并可在回答中说明来源。\n\n【检索到的文档片段】\n");
        for (Document d : docs) {
            String name = (String) d.getMetadata().get(METADATA_DOC_NAME);
            context.append("- 来源: ").append(name != null ? name : "未知").append("\n");
            context.append("  内容: ").append(d.getText()).append("\n\n");
        }
        context.append("请仅根据上述内容作答，不要编造未出现的信息。");
        return context.toString();
    }
}
