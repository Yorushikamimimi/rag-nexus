package com.ragnexus.kb.service;

import com.ragnexus.kb.domain.dto.KbChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagChatServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void streamChatSkipsBlankModelDeltasInsteadOfEmittingFallback() {
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(new Document("test evidence")));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any(ChatOptions.class))).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(Flux.just("第一段", "", "  ", "第二段"));

        RagChatService service = new RagChatService(vectorStore, chatClient);
        KbChatRequest request = KbChatRequest.builder()
                .sessionId("test-session")
                .query("test question")
                .build();

        List<String> chunks = service.streamChat(request).collectList().block();

        assertEquals(List.of("第一段", "第二段"), chunks);
    }
}
