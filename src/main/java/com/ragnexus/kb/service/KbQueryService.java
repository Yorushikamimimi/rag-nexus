package com.ragnexus.kb.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class KbQueryService {

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
}

