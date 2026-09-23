package com.ragnexus.kb.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KbQueryServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void getChunkPreviewUsesParameterizedNameAndCapsRowsAndText() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KbQueryService service = new KbQueryService(jdbcTemplate);
        String docName = "notes' OR 1=1 --.md";
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(docName))).thenReturn(51L);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Map<String, Object>> rowMapper = invocation.getArgument(1);
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (int i = 0; i < 51; i++) {
                        ResultSet resultSet = mock(ResultSet.class);
                        when(resultSet.getString("content"))
                                .thenReturn(i == 0 ? "界".repeat(1201) : "synthetic chunk " + i);
                        rows.add(rowMapper.mapRow(resultSet, i));
                    }
                    return rows;
                });

        Map<String, Object> preview = service.getChunkPreview(docName);

        assertEquals(51L, preview.get("totalChunks"));
        assertEquals(50, preview.get("displayedChunks"));
        assertEquals(true, preview.get("found"));
        assertEquals(true, preview.get("truncated"));
        List<Map<String, Object>> chunks = (List<Map<String, Object>>) preview.get("chunks");
        assertEquals(1200, ((String) chunks.get(0).get("text")).codePointCount(0, ((String) chunks.get(0).get("text")).length()));
        assertEquals(true, chunks.get(0).get("truncated"));
        assertTrue(((String) preview.get("orderingNote")).contains("无法还原原文顺序"));

        verify(jdbcTemplate).queryForObject(
                "SELECT COUNT(*) FROM vector_store WHERE metadata->>'docName' = ?",
                Long.class,
                docName
        );
        verify(jdbcTemplate).query(
                org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("metadata->>'docName' = ?") && !sql.contains(docName)),
                any(RowMapper.class),
                any(Object[].class)
        );
    }

    @Test
    void getChunkPreviewForMissingDocumentIsExplicitAndDoesNotReadRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        KbQueryService service = new KbQueryService(jdbcTemplate);
        String docName = "missing.md";
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(docName))).thenReturn(0L);

        Map<String, Object> preview = service.getChunkPreview(docName);

        assertFalse((Boolean) preview.get("found"));
        assertEquals(0L, preview.get("totalChunks"));
        assertEquals(List.of(), preview.get("chunks"));
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }
}
