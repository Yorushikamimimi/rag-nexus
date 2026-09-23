package com.ragnexus.kb.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * RAG 对话请求体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "RAG 智能对话请求")
public class KbChatRequest {

    @Schema(description = "会话 ID，用于多轮对话上下文", example = "employee-handbook-session-001")
    private String sessionId;

    @NotBlank(message = "query 不能为空")
    @Schema(description = "用户问题", example = "员工手册中规定的年假申请流程是什么？", requiredMode = Schema.RequiredMode.REQUIRED)
    private String query;

    @Positive
    @Schema(description = "召回 Top-K 条相关切片", example = "3", defaultValue = "3")
    @Builder.Default
    private Integer topK = 3;

    @DecimalMin(value = "0.0", message = "temperature 不能小于 0")
    @DecimalMax(value = "2.0", message = "temperature 不能大于 2")
    @Schema(description = "LLM 温度", example = "0.3", defaultValue = "0.3")
    @Builder.Default
    private Double temperature = 0.3;
}
