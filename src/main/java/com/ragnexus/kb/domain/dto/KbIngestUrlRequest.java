package com.ragnexus.kb.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库 URL 注入请求体：通过调用外部爬虫服务抓取内容后入库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "知识库 URL 注入请求")
public class KbIngestUrlRequest {

    @NotBlank(message = "url 不能为空")
    @Pattern(regexp = "^https?://.+", message = "url 必须以 http:// 或 https:// 开头")
    @Schema(description = "待抓取的视频/页面链接", example = "https://www.bilibili.com/video/BV1xx411c7mD", requiredMode = Schema.RequiredMode.REQUIRED)
    private String url;
}
