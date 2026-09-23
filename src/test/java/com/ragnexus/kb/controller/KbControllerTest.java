package com.ragnexus.kb.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragnexus.kb.domain.dto.KbChatRequest;
import com.ragnexus.kb.domain.dto.KbChatResponse;
import com.ragnexus.kb.service.KbIngestionService;
import com.ragnexus.kb.service.KbQueryService;
import com.ragnexus.kb.service.RagChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KbController MVC 切片测试。
 *
 * <p>使用 @WebMvcTest 仅加载 Web 层，通过测试替身隔离 pgvector 与 LLM 依赖。
 * 验证：接口路由、@Valid 参数校验、统一 Result 响应结构、Service 异常的容错包装。
 */
@WebMvcTest(controllers = KbController.class)
@DisplayName("KbController 切片测试")
class KbControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KbIngestionService kbIngestionService;

    @MockitoBean
    private KbQueryService kbQueryService;

    @MockitoBean
    private RagChatService ragChatService;

    // ────────────────────────────────────────────────────────────────────────
    // POST /api/v1/kb/chat
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /chat - 正常请求：返回 code=200，data 包含 answer 与 citations")
    void chat_validRequest_returnsOkWithAnswer() throws Exception {
        KbChatResponse mockResp = KbChatResponse.builder()
                .answer("社区活动将在周六上午举行。")
                .citations(List.of("社区活动.md"))
                .build();
        when(ragChatService.chat(any(KbChatRequest.class))).thenReturn(mockResp);

        KbChatRequest request = KbChatRequest.builder()
                .query("社区活动什么时候举行？")
                .topK(3)
                .temperature(0.3)
                .build();

        mockMvc.perform(post("/api/v1/kb/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.answer").value("社区活动将在周六上午举行。"))
                .andExpect(jsonPath("$.data.citations[0]").value("社区活动.md"));
    }

    @Test
    @DisplayName("POST /chat - query 为空白字符串：@NotBlank 校验触发，HTTP 400，code=400")
    void chat_blankQuery_returnsBadRequest() throws Exception {
        KbChatRequest request = KbChatRequest.builder()
                .query("   ")   // 触发 @NotBlank
                .topK(3)
                .temperature(0.3)
                .build();

        mockMvc.perform(post("/api/v1/kb/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ────────────────────────────────────────────────────────────────────────
    // GET /api/v1/kb/stats
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /stats - 正常返回：code=200，data 包含 totalChunks 与 totalDocs")
    void stats_returnsOkWithStatistics() throws Exception {
        when(kbQueryService.getStats()).thenReturn(Map.of(
                "totalChunks", 42,
                "totalDocs", 5
        ));

        mockMvc.perform(get("/api/v1/kb/stats"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalChunks").value(42))
                .andExpect(jsonPath("$.data.totalDocs").value(5));
    }

    @Test
    @DisplayName("GET /stats - Service 抛异常：冒泡到 GlobalExceptionHandler，HTTP 5xx，脱敏 message")
    void stats_serviceThrows_returns5xx() throws Exception {
        when(kbQueryService.getStats()).thenThrow(new RuntimeException("pgvector 连接超时"));

        mockMvc.perform(get("/api/v1/kb/stats"))
                .andDo(print())
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value(500))
                // message 已脱敏，不再回传原始异常文本
                .andExpect(jsonPath("$.message").value(containsString("服务器内部错误")));
    }

    @Test
    @DisplayName("GET /chunks - 返回已入库文本预览及顺序边界")
    void chunkPreview_returnsPreview() throws Exception {
        when(kbQueryService.getChunkPreview("河岸活动.md")).thenReturn(Map.of(
                "docName", "河岸活动.md",
                "found", true,
                "totalChunks", 1,
                "displayedChunks", 1,
                "maxChunks", 50,
                "maxCharactersPerChunk", 1200,
                "truncated", false,
                "orderingNote", "无法还原原文顺序",
                "chunks", List.of(Map.of(
                        "text", "合成活动资料",
                        "truncated", false,
                        "characterCount", 6
                ))
        ));

        mockMvc.perform(get("/api/v1/kb/chunks").param("docName", "河岸活动.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.found").value(true))
                .andExpect(jsonPath("$.data.chunks[0].text").value("合成活动资料"))
                .andExpect(jsonPath("$.data.orderingNote").value(containsString("无法还原原文顺序")));
    }

    @Test
    @DisplayName("GET /chunks - 未提供文件名时返回 HTTP 400")
    void chunkPreview_missingDocName_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/kb/chunks"))
                .andExpect(status().isBadRequest());
    }

    // ────────────────────────────────────────────────────────────────────────
    // POST /api/v1/kb/ingest
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /ingest - 正常注入：返回 code=200，data 含 docName 与 chunksCreated")
    void ingest_validRequest_returnsOkWithChunkCount() throws Exception {
        when(kbIngestionService.ingest(any())).thenReturn(8);

        String body = """
                {
                  "docName": "社区活动.md",
                  "content": "社区活动安排说明：活动将在周六上午举行。",
                  "chunkSize": 500
                }
                """;

        mockMvc.perform(post("/api/v1/kb/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.docName").value("社区活动.md"))
                .andExpect(jsonPath("$.data.chunksCreated").value(8));
    }

    @Test
    @DisplayName("POST /upload - 文件无可入库切片：HTTP 400，已有向量保持不变")
    void upload_noIndexableChunks_returnsBadRequest() throws Exception {
        when(kbIngestionService.ingestFromFile(any()))
                .thenThrow(new IllegalArgumentException(
                        "未生成可入库切片；本次未写入新向量，已有向量保持不变。请检查文件是否包含可提取文本。"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.pdf", "application/pdf", "synthetic-pdf".getBytes());

        mockMvc.perform(multipart("/api/v1/kb/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        "未生成可入库切片；本次未写入新向量，已有向量保持不变。请检查文件是否包含可提取文本。"));
    }

    @Test
    @DisplayName("POST /ingest - docName 缺失：@NotBlank 校验触发，HTTP 400")
    void ingest_missingDocName_returnsBadRequest() throws Exception {
        String body = """
                {
                  "content": "内容不能没有文档名",
                  "chunkSize": 500
                }
                """;

        mockMvc.perform(post("/api/v1/kb/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /ingest/url - 路由已移除且未调用注入服务")
    void ingestUrl_removedRoute_returnsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/kb/ingest/url")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/article\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        org.mockito.Mockito.verifyNoInteractions(kbIngestionService);
    }
}
