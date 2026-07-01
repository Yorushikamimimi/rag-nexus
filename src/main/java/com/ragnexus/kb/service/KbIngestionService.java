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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
 * 三源（文本 / URL / 文件）共用 splitAndStore 公共管道。依赖 EmbeddingModel 由 PgVectorStore 内部使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbIngestionService {

    private static final String METADATA_DOC_NAME = "docName";
    private static final String METADATA_AUTHOR = "author";
    private static final String METADATA_SOURCE_TYPE = "sourceType";
    private static final int DEFAULT_CHUNK_SIZE = 500;

    @Value("${scraper.service.url:http://127.0.0.1:8000}")
    private String scraperServiceBaseUrl;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RestTemplate REST_TEMPLATE = createRestTemplate();

    /**
     * 带连接/读取超时的 RestTemplate，避免爬虫服务挂起时 ingestFromUrl 永久阻塞拖垮线程池。
     */
    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);   // 5s
        factory.setReadTimeout(30000);     // 30s，yt-dlp 抓取较慢
        return new RestTemplate(factory);
    }

    private final VectorStore vectorStore;

    /**
     * 三源共用的分块 + metadata 写入管道。
     *
     * @param sourceDocs 待分块的 Document（单条文本请先包装成 new Document(content)）
     * @param metadata   每个切片要写入的元数据（docName / author / sourceType）
     * @param chunkSize  分块 token 数，null 走默认 500
     * @return 实际写入的 chunk 数
     */
    private int splitAndStore(List<Document> sourceDocs, Map<String, Object> metadata, Integer chunkSize) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize != null ? chunkSize : DEFAULT_CHUNK_SIZE)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(1)
                .build();
        List<Document> chunks = splitter.apply(sourceDocs);
        List<Document> documentsWithMeta = chunks.stream()
                .map(doc -> new Document(doc.getText(), metadata))
                .collect(Collectors.toList());
        vectorStore.add(documentsWithMeta);
        return documentsWithMeta.size();
    }

    /**
     * 对请求文本做分块，将 docName/author/sourceType 写入每个 Document 的 metadata，再写入 vector_store。
     */
    public int ingest(KbIngestRequest request) {
        try {
            Map<String, Object> metadata = Map.of(
                    METADATA_DOC_NAME, request.getDocName() != null ? request.getDocName() : "unknown",
                    METADATA_AUTHOR, request.getAuthor() != null ? request.getAuthor() : "System",
                    METADATA_SOURCE_TYPE, request.getSourceType() != null ? request.getSourceType() : "TXT"
            );
            int chunks = splitAndStore(List.of(new Document(request.getContent())), metadata, request.getChunkSize());
            log.info("Ingest success: docName={}, chunks={}", request.getDocName(), chunks);
            return chunks;
        } catch (Exception e) {
            log.error("KbIngestionService.ingest failed: docName={}", request.getDocName(), e);
            throw new RuntimeException("知识库注入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 调用外部爬虫服务抓取 URL 内容，提取 title/description 后分块入库。
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
        }

        ScraperExtractResponse response;
        try {
            response = OBJECT_MAPPER.readValue(responseJson, ScraperExtractResponse.class);
        } catch (Exception e) {
            log.error("解析爬虫响应 JSON 失败: url={}", url, e);
            throw new RuntimeException("调用外部爬虫服务失败: 响应解析异常", e);
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

        Map<String, Object> metadata = Map.of(
                METADATA_DOC_NAME, title,
                METADATA_AUTHOR, uploader,
                METADATA_SOURCE_TYPE, "URL"
        );
        int chunks = splitAndStore(List.of(new Document(content)), metadata, DEFAULT_CHUNK_SIZE);
        log.info("IngestFromUrl success: url={}, docName={}, chunks={}", url, title, chunks);
        return chunks;
    }

    /**
     * 使用 TikaDocumentReader 解析本地多模态文件（PDF、DOC、PPT 等），分块后入库。
     */
    public int ingestFromFile(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        try (InputStream inputStream = file.getInputStream()) {
            InputStreamResource resource = new InputStreamResource(inputStream);
            TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
            List<Document> documents = tikaReader.get();

            if (documents == null || documents.isEmpty()) {
                log.warn("Tika 解析未提取到内容: filename={}", filename);
                throw new RuntimeException("文件解析失败: 未提取到有效文本内容");
            }

            Map<String, Object> metadata = Map.of(
                    METADATA_DOC_NAME, filename,
                    METADATA_AUTHOR, "Upload",
                    METADATA_SOURCE_TYPE, "FILE"
            );
            int chunks = splitAndStore(documents, metadata, DEFAULT_CHUNK_SIZE);
            log.info("IngestFromFile success: filename={}, chunks={}", filename, chunks);
            return chunks;
        } catch (IOException e) {
            log.error("文件读取或解析失败: filename={}", filename, e);
            throw new RuntimeException("文件解析失败: " + e.getMessage(), e);
        }
    }
}
