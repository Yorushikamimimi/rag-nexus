package com.ragnexus.kb.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RAG 对话响应：答案 + 引用来源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "RAG 对话响应")
public class KbChatResponse {

    @Schema(description = "模型生成的回答")
    private String answer;

    @Schema(description = "引用文档列表（来自 metadata docName）")
    private List<String> citations;
}
