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
import java.util.Objects;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatService {

    private static final String METADATA_DOC_NAME = "docName";
    private static final String FALLBACK_ANSWER = "当前无法完成知识检索或模型生成，请稍后重试。";
    private static final String DEFAULT_SESSION_ID = "default";

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public KbChatResponse chat(KbChatRequest request) {
        int topK = request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : 3;
        String query = request.getQuery();
        double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;

        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(topK).build()
            );

            String systemPrompt = buildRagSystemPrompt(docs);
            List<String> citations = docs.stream()
                    .map(d -> (String) d.getMetadata().get(METADATA_DOC_NAME))
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank()
                    ? request.getSessionId()
                    : DEFAULT_SESSION_ID;

            ChatOptions options = ChatOptions.builder().temperature(temperature).build();
            String answer = chatClient.prompt()
                    .system(systemPrompt)
                    .user(query)
                    .options(options)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();

            answer = normalizeGarbledAnswer(answer, query);
            if (isLikelyGarbled(answer)) {
                answer = buildEvidenceFallback(query, docs);
            }

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

    public Flux<String> streamChat(KbChatRequest request) {
        int topK = request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : 3;
        String query = request.getQuery();
        double temperature = request.getTemperature() != null ? request.getTemperature() : 0.3;

        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(topK).build()
            );

            String systemPrompt = buildRagSystemPrompt(docs);
            String sessionId = request.getSessionId() != null && !request.getSessionId().isBlank()
                    ? request.getSessionId()
                    : DEFAULT_SESSION_ID;

            ChatOptions options = ChatOptions.builder().temperature(temperature).build();

            return chatClient.prompt()
                    .system(systemPrompt)
                    .user(query)
                    .options(options)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .content()
                    .map(chunk -> normalizeGarbledAnswer(chunk, query));
        } catch (Exception e) {
            log.error("RagChatService.streamChat failed: query={}", query, e);
            return Flux.just(FALLBACK_ANSWER);
        }
    }

    private String normalizeGarbledAnswer(String answer, String query) {
        if (answer == null || answer.isBlank()) {
            return FALLBACK_ANSWER;
        }
        String cleaned = answer;
        if (query != null && !query.isBlank()) {
            cleaned = cleaned.replaceAll("([（(])[?？]{2,}([）)])", "$1" + query + "$2");
            cleaned = cleaned.replace("“??”", "“" + query + "”");
            cleaned = cleaned.replace("\"??\"", "\"" + query + "\"");
        }
        cleaned = cleaned.replaceAll("[?？]{6,}", "（文本编码异常片段已省略）");
        return cleaned;
    }

    private boolean isLikelyGarbled(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return text.contains("问题内容为问号")
                || text.contains("输入的是“??”")
                || text.contains("（文本编码异常片段已省略）");
    }

    private String buildEvidenceFallback(String query, List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "未检索到可用片段，无法依据知识库回答。请尝试补充关键词或先导入相关资料。";
        }

        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("基于当前知识库片段，给出可确认的信息：");

        int limit = Math.min(3, docs.size());
        for (int i = 0; i < limit; i++) {
            Document d = docs.get(i);
            String name = (String) d.getMetadata().getOrDefault(METADATA_DOC_NAME, "unknown");
            String snippet = d.getText() == null ? "" : d.getText().replaceAll("\\s+", " ").trim();
            if (snippet.length() > 120) {
                snippet = snippet.substring(0, 120) + "...";
            }
            joiner.add((i + 1) + ". [" + name + "] " + snippet);
        }

        joiner.add("若你希望我继续深入，请给出更具体的问题（例如：主题、时间线、争议点、引用出处）。");
        if (query != null && !query.isBlank()) {
            joiner.add("当前问题：" + query);
        }
        return joiner.toString();
    }

    private String buildRagSystemPrompt(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return """
                    你是一个知识库问答助手。
                    当前没有检索到相关文档片段，请明确告知用户“当前知识库无直接证据”，
                    并给出可执行的下一步建议（例如补充关键词或导入资料）。
                    不要编造来源。
                    """;
        }

        StringBuilder context = new StringBuilder();
        context.append("""
                你是一个严格基于知识库证据回答问题的助手。
                回答要求：
                1) 仅依据下方“检索片段”作答，不要编造未出现的信息。
                2) 如果证据不足，明确说明“不足以判断”。
                3) 回答尽量结构化、简洁，必要时列要点。

                【检索片段】
                """);

        for (Document d : docs) {
            String name = (String) d.getMetadata().get(METADATA_DOC_NAME);
            context.append("- 来源: ").append(name != null ? name : "unknown").append('\n');
            context.append("  内容: ").append(d.getText()).append("\n\n");
        }

        context.append("请基于上述片段回答用户问题。");
        return context.toString();
    }
}
