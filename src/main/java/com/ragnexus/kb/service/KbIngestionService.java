package com.ragnexus.kb.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragnexus.kb.domain.dto.KbIngestRequest;
import com.ragnexus.kb.domain.dto.ScraperExtractResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识注入服务：文本分块 + 元数据写入 Document.metadata，存入 Spring AI 默认 vector_store。
 * 依赖 EmbeddingModel 由 PgVectorStore 内部使用，本层仅使用 VectorStore。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbIngestionService {

    private static final String METADATA_DOC_NAME = "docName";
    private static final String METADATA_AUTHOR = "author";
    private static final String METADATA_SOURCE_TYPE = "sourceType";

    @Value("${scraper.service.url:http://127.0.0.1:8000}")
    private String scraperServiceBaseUrl;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RestTemplate REST_TEMPLATE = new RestTemplate();

    private final VectorStore vectorStore;

    /**
     * 对请求文本做分块，将 docName/author/sourceType 写入每个 Document 的 metadata，再写入 vector_store。
     * 异常时 Fallback：记录日志并抛出业务异常，由 Controller 统一封装为 Result.fail。
     */
    public int ingest(KbIngestRequest request) {
        try {
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(request.getChunkSize() != null ? request.getChunkSize() : 500)
                    .withMinChunkSizeChars(100)
                    .withMinChunkLengthToEmbed(1)
                    .build();

            Document initialDoc = new Document(request.getContent());
            List<Document> chunks = splitter.apply(List.of(initialDoc));

            Map<String, Object> metadata = Map.of(
                    METADATA_DOC_NAME, request.getDocName() != null ? request.getDocName() : "unknown",
                    METADATA_AUTHOR, request.getAuthor() != null ? request.getAuthor() : "System",
                    METADATA_SOURCE_TYPE, request.getSourceType() != null ? request.getSourceType() : "TXT"
            );

            List<Document> documentsWithMeta = chunks.stream()
                    .map(doc -> new Document(doc.getText(), metadata))
                    .collect(Collectors.toList());

            vectorStore.add(documentsWithMeta);
            log.info("Ingest success: docName={}, chunks={}", request.getDocName(), documentsWithMeta.size());
            return documentsWithMeta.size();
        } catch (Exception e) {
            log.error("KbIngestionService.ingest failed: docName={}", request.getDocName(), e);
            throw new RuntimeException("知识库注入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用外部爬虫服务抓取 URL 内容，提取 title/description 后分块入库。
     * 使用 RestTemplate + HttpEntity 显式设置 Content-Type，兼容 FastAPI。
     */
    public int ingestFromUrl(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(Map.of("url", url), headers);

        String responseJson;
        try {
            responseJson = REST_TEMPLATE.postForObject(scraperServiceBaseUrl + "/api/v1/scraper/extract", entity, String.class);
        } catch (RestClientException e) {
            log.error("调用外部爬虫服务失败: url={}", url, e);
            throw new RuntimeException("调用外部爬虫服务失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("调用外部爬虫服务失败（反序列化或网络异常）: url={}", url, e);
            throw new RuntimeException("调用外部爬虫服务失败: " + e.getMessage(), e);
        }

        ScraperExtractResponse response;
        try {
            response = OBJECT_MAPPER.readValue(responseJson, ScraperExtractResponse.class);
        } catch (Exception e) {
            log.error("解析爬虫响应 JSON 失败: url={}", url, e);
            throw new RuntimeException("调用外部爬虫服务失败: 响应解析异常 " + e.getMessage(), e);
        }

        if (response == null || response.getData() == null || response.getCode() != 200) {
            String msg = response != null ? response.getMessage() : "爬虫服务返回空响应";
            log.warn("爬虫抓取失败: url={}, message={}", url, msg);
            throw new RuntimeException("调用外部爬虫服务失败: " + msg);
        }

        ScraperExtractResponse.ScraperExtractData data = response.getData();
        String title = data.getTitle() != null ? data.getTitle() : "Unknown";
        String description = data.getDescription() != null ? data.getDescription() : "";
        String content = String.format("%s\n\n%s", title, description).trim();
        String uploader = data.getUploader() != null ? data.getUploader() : "System";

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(1)
                .build();

        Document initialDoc = new Document(content);
        List<Document> chunks = splitter.apply(List.of(initialDoc));

        Map<String, Object> metadata = Map.of(
                METADATA_DOC_NAME, title,
                METADATA_AUTHOR, uploader,
                METADATA_SOURCE_TYPE, "URL"
        );

        List<Document> documentsWithMeta = chunks.stream()
                .map(doc -> new Document(doc.getText(), metadata))
                .collect(Collectors.toList());

        vectorStore.add(documentsWithMeta);
        log.info("IngestFromUrl success: url={}, docName={}, chunks={}", url, title, documentsWithMeta.size());
        return documentsWithMeta.size();
    }

    /**
     * 使用 TikaDocumentReader 解析本地多模态文件（PDF、DOC、PPT 等），分块后入库。
     * 将原始文件名附加到切片 metadata.source，便于溯源。
     */
    public int ingestFromFile(org.springframework.web.multipart.MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        try (InputStream inputStream = file.getInputStream()) {
            InputStreamResource resource = new InputStreamResource(inputStream);
            TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
            List<Document> documents = tikaReader.get();

            if (documents == null || documents.isEmpty()) {
                log.warn("Tika 解析未提取到内容: filename={}", filename);
                throw new RuntimeException("文件解析失败: 未提取到有效文本内容");
            }

            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(500)
                    .withMinChunkSizeChars(100)
                    .withMinChunkLengthToEmbed(1)
                    .build();

            List<Document> chunks = splitter.apply(documents);

            Map<String, Object> baseMetadata = Map.of(
                    METADATA_DOC_NAME, filename,
                    METADATA_AUTHOR, "Upload",
                    METADATA_SOURCE_TYPE, "FILE",
                    "source", filename
            );

            List<Document> documentsWithMeta = chunks.stream()
                    .map(doc -> new Document(doc.getText(), baseMetadata))
                    .collect(Collectors.toList());

            vectorStore.add(documentsWithMeta);
            log.info("IngestFromFile success: filename={}, chunks={}", filename, documentsWithMeta.size());
            return documentsWithMeta.size();
        } catch (IOException e) {
            log.error("文件读取或解析失败: filename={}", filename, e);
            throw new RuntimeException("文件解析失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("KbIngestionService.ingestFromFile failed: filename={}", filename, e);
            throw new RuntimeException("知识库文件注入失败: " + e.getMessage(), e);
        }
    }
}
