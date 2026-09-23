package com.ragnexus.kb.service;

import com.ragnexus.kb.domain.dto.KbIngestRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识注入服务：文本分块 + 元数据写入 Document.metadata，存入 Spring AI 默认 vector_store。
 * 文本与文件共用 splitAndStore 公共管道。依赖 EmbeddingModel 由 PgVectorStore 内部使用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbIngestionService {

    private static final String METADATA_DOC_NAME = "docName";
    private static final String METADATA_AUTHOR = "author";
    private static final String METADATA_SOURCE_TYPE = "sourceType";
    private static final int DEFAULT_CHUNK_SIZE = 500;
    private static final String NO_INDEXABLE_CHUNKS_MESSAGE =
            "未生成可入库切片；本次未写入新向量，已有向量保持不变。请检查文件是否包含可提取文本。";

    private static final class NoIndexableChunksException extends IllegalArgumentException {
        private NoIndexableChunksException(String message) {
            super(message);
        }
    }

    private final VectorStore vectorStore;

    /**
     * 文本与文件共用的分块 + metadata 写入管道。
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
        if (chunks.isEmpty()) {
            throw new NoIndexableChunksException(NO_INDEXABLE_CHUNKS_MESSAGE);
        }
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
        } catch (NoIndexableChunksException e) {
            throw e;
        } catch (Exception e) {
            log.error("KbIngestionService.ingest failed: docName={}", request.getDocName(), e);
            throw new RuntimeException("知识库注入失败: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 TikaDocumentReader 提取本地文件中的文本，分块后入库。
     */
    public int ingestFromFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new NoIndexableChunksException(NO_INDEXABLE_CHUNKS_MESSAGE);
        }
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
        try (InputStream inputStream = file.getInputStream()) {
            InputStreamResource resource = new InputStreamResource(inputStream);
            TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
            List<Document> documents = tikaReader.get();

            if (documents == null || documents.isEmpty()) {
                log.warn("Tika 解析未提取到内容: filename={}", filename);
                throw new NoIndexableChunksException(NO_INDEXABLE_CHUNKS_MESSAGE);
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
