package com.ragnexus.kb.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 知识库注入请求体：元数据通过 metadata 写入 vector_store。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "知识库注入请求")
public class KbIngestRequest {

    @NotBlank(message = "docName 不能为空")
    @Schema(description = "文档名称", example = "思想犯_Lyrics_Analysis.md", requiredMode = Schema.RequiredMode.REQUIRED)
    private String docName;

    @NotBlank(message = "content 不能为空")
    @Schema(description = "原始文本内容", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @NotNull
    @Positive
    @Schema(description = "分块大小（token 数）", example = "500", defaultValue = "500")
    @Builder.Default
    private Integer chunkSize = 500;

    @NotNull
    @Schema(description = "块间重叠（token 数）", example = "50", defaultValue = "50")
    @Builder.Default
    private Integer overlap = 50;

    @Schema(description = "作者", example = "n-buna", defaultValue = "System")
    @Builder.Default
    private String author = "System";

    @Schema(description = "来源类型", example = "MARKDOWN", defaultValue = "TXT")
    @Builder.Default
    private String sourceType = "TXT";
}
