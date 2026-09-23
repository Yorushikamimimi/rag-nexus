package com.ragnexus.kb.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KbQueryService {

    private static final int MAX_PREVIEW_CHUNKS = 50;
    private static final int MAX_PREVIEW_CODE_POINTS = 1200;

    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> getStats() {
        Long totalChunks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store",
                Long.class
        );

        Long totalDocs = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT COALESCE(metadata->>'docName', 'unknown')) FROM vector_store",
                Long.class
        );

        List<Map<String, Object>> documents = jdbcTemplate.query(
                """
                SELECT COALESCE(metadata->>'docName', 'unknown') AS doc_name,
                       COUNT(*) AS chunk_count
                FROM vector_store
                GROUP BY COALESCE(metadata->>'docName', 'unknown')
                ORDER BY chunk_count DESC, doc_name ASC
                LIMIT 200
                """,
                (rs, rowNum) -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("docName", rs.getString("doc_name"));
                    row.put("chunks", rs.getLong("chunk_count"));
                    return row;
                }
        );

        Map<String, Object> data = new HashMap<>();
        data.put("totalChunks", totalChunks == null ? 0L : totalChunks);
        data.put("totalDocs", totalDocs == null ? 0L : totalDocs);
        data.put("documents", documents);
        return data;
    }

    public Map<String, Object> getChunkPreview(String docName) {
        if (docName == null || docName.isBlank()) {
            throw new IllegalArgumentException("docName 不能为空");
        }

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'docName' = ?",
                Long.class,
                docName
        );
        long totalChunks = count == null ? 0L : count;

        List<Map<String, Object>> chunks = totalChunks == 0
                ? List.of()
                : jdbcTemplate.query(
                        """
                        SELECT content
                        FROM vector_store
                        WHERE metadata->>'docName' = ?
                        ORDER BY id ASC
                        LIMIT ?
                        """,
                        (rs, rowNum) -> {
                            String content = rs.getString("content");
                            if (content == null) {
                                content = "";
                            }
                            int codePoints = content.codePointCount(0, content.length());
                            boolean truncated = codePoints > MAX_PREVIEW_CODE_POINTS;
                            String preview = truncated
                                    ? content.substring(0, content.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS))
                                    : content;

                            Map<String, Object> chunk = new LinkedHashMap<>();
                            chunk.put("text", preview);
                            chunk.put("truncated", truncated);
                            chunk.put("characterCount", codePoints);
                            return chunk;
                        },
                        docName,
                        MAX_PREVIEW_CHUNKS + 1
                );

        boolean tooManyChunks = chunks.size() > MAX_PREVIEW_CHUNKS;
        if (tooManyChunks) {
            chunks = chunks.subList(0, MAX_PREVIEW_CHUNKS);
        }
        boolean contentTruncated = chunks.stream().anyMatch(chunk -> Boolean.TRUE.equals(chunk.get("truncated")));

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("docName", docName);
        preview.put("found", totalChunks > 0);
        preview.put("totalChunks", totalChunks);
        preview.put("displayedChunks", chunks.size());
        preview.put("maxChunks", MAX_PREVIEW_CHUNKS);
        preview.put("maxCharactersPerChunk", MAX_PREVIEW_CODE_POINTS);
        preview.put("truncated", tooManyChunks || contentTruncated);
        preview.put("orderingNote", "片段按数据库 ID 展示；当前记录没有可靠的原文分块序号，无法还原原文顺序。相同文件名的入库切片会合并显示。");
        preview.put("chunks", chunks);
        return preview;
    }
}
