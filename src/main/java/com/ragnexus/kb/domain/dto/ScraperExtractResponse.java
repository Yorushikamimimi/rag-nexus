package com.ragnexus.kb.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Python 爬虫服务 /api/v1/scraper/extract 响应体。
 * 用于解析 data.title、data.description。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScraperExtractResponse {

    private int code;
    private String message;
    private ScraperExtractData data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScraperExtractData {
        private String title;
        private String description;
        private String uploader;
    }
}
