package com.ragnexus.kb.controller;

import com.ragnexus.kb.common.Result;
import com.ragnexus.kb.domain.dto.KbChatRequest;
import com.ragnexus.kb.domain.dto.KbChatResponse;
import com.ragnexus.kb.domain.dto.KbIngestRequest;
import com.ragnexus.kb.domain.dto.KbIngestUrlRequest;
import com.ragnexus.kb.service.KbIngestionService;
import com.ragnexus.kb.service.KbQueryService;
import com.ragnexus.kb.service.RagChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 知识库 REST：注入 + RAG 对话。表结构采用 Spring AI 默认 vector_store，元数据存 Document.metadata。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
@Tag(name = "知识库", description = "知识库注入与 RAG 智能对话")
public class KbController {

    private final KbIngestionService kbIngestionService;
    private final KbQueryService kbQueryService;
    private final RagChatService ragChatService;

    @Operation(summary = "知识库注入", description = "接收文本，分块后写入 vector_store，元数据写入 Document.metadata")
    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Map<String, Object>> ingest(@Valid @RequestBody KbIngestRequest request) {
        try {
            int chunks = kbIngestionService.ingest(request);
            return Result.ok(Map.of(
                    "docName", request.getDocName(),
                    "chunksCreated", chunks
            ));
        } catch (Exception e) {
            log.warn("ingest failed: {}", e.getMessage());
            return Result.fail("知识库注入失败: " + e.getMessage());
        }
    }

    @Operation(summary = "URL 注入", description = "调用外部爬虫服务抓取 URL 内容，分块后写入 vector_store")
    @PostMapping(value = "/ingest/url", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Map<String, Object>> ingestFromUrl(@Valid @RequestBody KbIngestUrlRequest request) {
        try {
            int chunks = kbIngestionService.ingestFromUrl(request.getUrl());
            return Result.ok(Map.of(
                    "url", request.getUrl(),
                    "chunksCreated", chunks
            ));
        } catch (Exception e) {
            log.warn("ingestFromUrl failed: {}", e.getMessage());
            return Result.fail(e.getMessage());
        }
    }

    @Operation(summary = "文件上传注入", description = "上传本地多模态文档（PDF、DOC、PPT 等），使用 Tika 解析后分块入库")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                return Result.fail("请选择要上传的文件");
            }
            int chunks = kbIngestionService.ingestFromFile(file);
            return Result.ok(Map.of(
                    "filename", file.getOriginalFilename(),
                    "chunksCreated", chunks
            ));
        } catch (Exception e) {
            log.warn("upload failed: {}", e.getMessage());
            return Result.fail("文件注入失败: " + e.getMessage());
        }
    }

    @Operation(summary = "RAG 智能对话", description = "相似度检索 Top-K 切片，拼装 System Prompt 后调用 LLM 生成回答")
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<KbChatResponse> chat(@Valid @RequestBody KbChatRequest request) {
        KbChatResponse data = ragChatService.chat(request);
        return Result.ok(data);
    }

    @GetMapping(value = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public Result<Map<String, Object>> stats() {
        try {
            return Result.ok(kbQueryService.getStats());
        } catch (Exception e) {
            log.warn("stats failed: {}", e.getMessage());
            return Result.fail("获取知识库统计失败: " + e.getMessage());
        }
    }

    @Operation(summary = "RAG 流式对话", description = "SSE 流式输出，实时推送 LLM 生成内容，降低首字延迟")
    @PostMapping(value = "/chat/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@Valid @RequestBody KbChatRequest request) {
        return ragChatService.streamChat(request);
    }
}
