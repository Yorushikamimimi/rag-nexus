# Failure Cases

## 文档目的

本文档记录 RAG-Nexus 在真实使用中可能出现的失败模式，包含每个问题在当前代码中的具体体现、原因分析和可行的改进方向。

写这份文档的目的不是掩盖问题，而是清晰划定这个工程原型的边界：哪些功能已经可用、哪些场景下会失效、为什么会失效。能准确描述一个系统的失败模式，往往比说"这个系统很好用"更能说明问题。

---

## Failure Case 1：文本切块导致上下文断裂

### 现象

用户提问涉及一段连贯叙述（如文章某一段的逻辑论证、歌词的情感递进），但回答缺失关键句，或前后文逻辑断裂。

### 原因

RAG 的核心假设是"检索到的片段足以支撑回答"。但这个假设依赖切块质量——如果一句关键语句恰好被切断，它要么落入前一个 chunk 的尾部、要么落入后一个 chunk 的头部，召回时可能两者都没拿到。

### 当前项目中的体现

`KbIngestionService.ingest()` 使用 `TokenTextSplitter`，`chunkSize` 从请求参数中读取（默认 500），但 `KbIngestRequest` 定义的 `overlap` 字段**从未传入 splitter**：

```java
// KbIngestionService.java
TokenTextSplitter splitter = TokenTextSplitter.builder()
        .withChunkSize(request.getChunkSize() != null ? request.getChunkSize() : 500)
        .withMinChunkSizeChars(100)
        .withMinChunkLengthToEmbed(1)
        .build();   // ← overlap 参数完全缺失，KbIngestRequest.overlap 是废字段
```

`ingestFromUrl()` 和 `ingestFromFile()` 中 `chunkSize` 更是直接硬编码为 500，不接受外部参数。

### 改进方向

- 修复最直接的 bug：将 `request.getOverlap()` 传入 `.withOverlapSizeTokens()`
- 对不同文档类型使用不同切块策略：Markdown 按标题切，代码文件按函数切，视频描述直接不切
- 引入语义分块（Semantic Chunking）替代固定 token 数，确保同一语义单元不被拆散

---

## Failure Case 2：扫描件 / 图片型 PDF 无法纳入知识库

### 现象

用户上传 PDF 文件，接口返回成功（HTTP 200），但后续问答完全召回不到相关内容，知识库统计中该文档的 chunk 数为 0 或接近 0。

### 原因

Apache Tika 只能解析 PDF 文档的**文本层**（即可被复制的文字）。扫描件、图片嵌入式 PDF 在 Tika 看来没有可提取的文本，属于正常行为。

### 当前项目中的体现

`ingestFromFile()` 调用 `TikaDocumentReader` 后会做空内容检测：

```java
// KbIngestionService.java
List<Document> documents = tikaReader.get();
if (documents == null || documents.isEmpty()) {
    log.warn("Tika 解析未提取到内容: filename={}", filename);
    throw new RuntimeException("文件解析失败: 未提取到有效文本内容");
}
```

`documents` 不为空（Tika 仍会返回一个 Document 对象），但其中的文本内容可能是空字符串。此时 `documents.isEmpty()` 为 false，检测不会触发，后续 `TokenTextSplitter` 的 `withMinChunkLengthToEmbed(1)` 也会将空白文本过滤掉，最终结果是注入了 0 个 chunk，但接口返回显示"成功"，用户无感知。

### 改进方向

- 在 `TokenTextSplitter` 之后、`vectorStore.add()` 之前检查 chunk 数量，若为 0 则返回明确的错误提示（"文件内容为空，可能是图片型 PDF，需先 OCR"）
- 引入 OCR 管道（如 Tesseract + Tika-OCR）处理图片型 PDF
- 在文件注入流程中加入内容质量校验层，拒绝无效文件入库，避免知识库"脏数据"

---

## Failure Case 3：URL 注入内容质量不稳定，召回质量波动

### 现象

通过 URL 注入视频后，用户提问视频内具体观点或细节，回答要么泛化（只说大方向）、要么答不出来；但问"这个视频叫什么名字"则可以正常回答。

### 原因

yt-dlp 的信息提取以**元数据**为主：标题、简介（description）、上传者。对于绝大多数 B 站和 YouTube 视频，简介字数有限，且通常是频道介绍、贴片广告文字、版权声明等，而不是视频内容本身的结构化信息。

### 当前项目中的体现

`ingestFromUrl()` 中，入库的文本是：

```java
// KbIngestionService.java
String content = String.format("%s\n\n%s", title, description).trim();
```

也就是 `视频标题 + 视频简介`，没有字幕、没有 AI 摘要、没有章节信息。一个 20 分钟的技术讲座，入库的可能只有 200 字的简介文本；通过 500 token 的 chunkSize 切分后，实际只产生 1 个 chunk，召回时也只能拿到这一块。

对于音乐、Vlog、生活类视频，简介内容和实际讨论话题的相关性更低。

### 改进方向

- 通过 yt-dlp 的字幕接口（`--write-auto-subs`）抓取自动生成字幕，将字幕文本作为正文入库
- 对于没有字幕的视频，使用 Whisper 进行语音识别后再入库
- 在 FastAPI 爬虫服务中增加内容质量评分（字数、信息密度），低于阈值时拒绝注入或给出警告
- 允许用户在 URL 注入时补充自定义摘要，作为额外上下文写入 metadata

---

## Failure Case 4：无相似度阈值，低质量召回直接进入 Prompt

### 现象

用户问了一个知识库中完全没有相关内容的问题，但 LLM 仍然给出了看起来"有依据"的回答，citations 里还列出了几个文档名，实际上那些文档和问题毫不相关。

### 原因

`VectorStore.similaritySearch()` 总会返回 topK 个结果，即使最相关的向量距离也很远。没有相似度分数过滤时，"最不相关"的文档也会被纳入 Prompt，LLM 可能基于这些低质量上下文生成看似合理但实际有误的回答。

### 当前项目中的体现

`RagChatService` 中的召回逻辑：

```java
// RagChatService.java
List<Document> docs = vectorStore.similaritySearch(
        SearchRequest.builder().query(query).topK(topK).build()
);
```

`SearchRequest` 没有设置 `similarityThreshold`，Spring AI 默认为 0（接受所有结果）。无论向量距离多远，topK 个结果都会拼进 System Prompt。

此外，topK 默认值 3 是固定的：简单事实问题（一个 chunk 够了）和跨文档综合性问题（需要 8-10 个 chunk）使用完全相同的召回数量，都不是最优。

### 改进方向

- 为 `SearchRequest` 设置 `similarityThreshold`（如 0.7），分数低于阈值的文档不进入 Prompt；当所有结果都低于阈值时，直接告知用户"知识库中无相关内容"
- 将 topK 保持为请求级可配（当前已支持），同时在文档中明确推荐值（简单问题 3，综合问题 8）
- 引入 Rerank 步骤（如 BGE-Reranker）对召回结果重排序，提高最终进入 Prompt 的文档质量

---

## Failure Case 5：会话记忆不持久，重启后多轮上下文丢失

### 现象

用户进行了一段多轮对话后，服务重启（或 Docker 容器重启），再次提问时 LLM 完全不记得之前的对话历史，用户需要从头说明背景。

### 原因

多轮记忆存储在进程内存中，进程结束即消失。

### 当前项目中的体现

`ChatMemoryConfig.java` 使用 `InMemoryChatMemoryRepository`：

```java
// ChatMemoryConfig.java
@Bean
public ChatMemory chatMemory() {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(10)
            .build();
}
```

会话历史以 `sessionId` 为 key 存在 JVM 堆内存的 `Map` 中。后端重启、Docker 容器重建、甚至 GC 极端情况下都会导致历史丢失。

另一个隐患：没有会话级用户隔离。如果两个不同用户碰巧使用了相同的 `sessionId`（如默认值 `"default"`），他们会共享同一段对话历史。

### 改进方向

- 替换为持久化实现：`JdbcChatMemoryRepository`（写入同一个 PostgreSQL）或 Redis-backed 实现
- 在 API 层对 `sessionId` 进行用户级隔离（与用户 ID 绑定），防止串话
- 为会话记忆设置过期策略（如 7 天无访问自动清除），避免内存无限增长

---

## Failure Case 6：Prompt 约束无法弥补检索质量不足

### 现象

Prompt 中明确要求"仅依据检索片段作答"，但当知识库内容本身质量差时（见 Case 1/3），LLM 可能给出以下几种错误表现：

- **泛化回答**：说了一堆正确的废话，和知识库片段关联不大
- **拒答不足**：应该说"不知道"，但给出了听起来合理的推断
- **幻觉引用**：citations 里列的文档名和回答内容实际上无关

### 原因

LLM 的预训练知识和 Prompt 中的检索片段同时存在于上下文窗口，模型不一定能严格区分"我从文档中学到的"和"我预训练时学到的"。当检索片段质量低（内容稀少、相关性弱）时，模型更倾向于依赖预训练知识填空，Prompt 约束的效力下降。

### 当前项目中的体现

`buildRagSystemPrompt()` 的约束策略：

```java
// RagChatService.java
"1) 仅依据下方"检索片段"作答，不要编造未出现的信息。\n"
"2) 如果证据不足，明确说明\"不足以判断\"。\n"
```

`isLikelyGarbled()` 的降级判断依赖特定字符串模式（如 `"问题内容为问号"`、`"（文本编码异常片段已省略）"`），只能捕获已知的乱码模式，无法检测"语义正确但事实错误"的幻觉回答。

此外，流式接口 `streamChat()` 中 `normalizeGarbledAnswer()` 逐 chunk 运行，但 `isLikelyGarbled + buildEvidenceFallback` 降级逻辑未接入流式路径——流式问答在触发幻觉时没有兜底，而同步问答有。

### 改进方向

- 为 `SearchRequest` 添加相似度阈值（见 Case 4），在根源上减少低质量片段进入 Prompt
- 在流式路径中补充 `isLikelyGarbled` 检测，保持与同步路径一致的降级能力
- 引入 Faithfulness 评估（如 RAGAS 框架）自动检测回答与检索片段的一致性
- 对召回结果展示相似度分数，让用户在 UI 侧感知知识库覆盖情况

> 注：流式 `normalizeGarbledAnswer()` 逐 chunk 在 token 粒度运行，正则模式跨 chunk 边界会失效，实际清洗能力有限。**当前版本接受为已知限制，不做硬修**；根因治理需在累积完整回答后再清洗，或引入 RAGAS 类评估。

---

## Failure Case 7：测试覆盖不足，Service 层质量依赖人工验证

### 现象

修改 `KbIngestionService` 或 `RagChatService` 的核心逻辑后，`mvn test` 全部通过，但实际行为已经发生了不易察觉的变化（如 overlap 参数突然生效了但切块结果与预期不符，或 Prompt 模板调整后召回引用格式变化）。

### 原因

当前测试只覆盖了 Controller 层，Service 层的业务逻辑完全没有自动化验证。

### 当前项目中的体现

现有测试文件仅有一个：

```
src/test/java/com/ragnexus/kb/controller/KbControllerTest.java
```

覆盖内容：HTTP 路由、`@Valid` 参数校验、`Result<T>` 响应格式、Controller 层异常包装（6 个用例，均通过 `@MockBean` 隔离 Service）。

未覆盖：

| 测试类型 | 缺失内容 |
|---|---|
| Service 单元测试 | `KbIngestionService.ingest()` 的分块逻辑、metadata 写入正确性 |
| Service 单元测试 | `RagChatService` 的 Prompt 构建逻辑、乱码检测、降级路径 |
| 集成测试 | `KbQueryService` 的 SQL 查询（需要 pgvector 或 Testcontainers） |
| 流式接口测试 | `streamChat()` 的 `Flux<String>` 输出格式与 SSE 行为 |
| 外部依赖测试 | `ingestFromUrl()` 调用爬虫服务的 HTTP 交互（WireMock） |

### 改进方向

- 对 `KbIngestionService.ingest()` 补充 Mockito 单元测试，验证：chunk 数是否符合预期、metadata 是否正确写入每个 Document
- 对 `RagChatService` 的私有方法提取为包可见，补充 `buildRagSystemPrompt()`、`isLikelyGarbled()`、`normalizeGarbledAnswer()` 的纯逻辑测试
- 对 `KbQueryService` 使用 Testcontainers + `pgvector/pgvector:pg16` 镜像做集成测试
- 对流式接口使用 `WebTestClient` + `StepVerifier` 验证 `Flux<String>` 输出序列

---

## 总结

上面这 7 个 failure case 不代表系统不可用。在功能完整性上，这个项目实现了 RAG 的完整链路：三源注入、向量召回、Prompt 拼装、流式输出、多轮记忆，可以在真实场景下完成基本的知识库问答任务。

这些 failure case 说明的是：这是一个**清楚自身边界的工程原型**。

- 分块 overlap 未生效是已知 bug，不是设计缺陷，可以一行代码修复
- 扫描件不支持是 Tika 的能力边界，不是项目的问题，需要 OCR 管道补充
- 测试覆盖不足是原型阶段的合理取舍，当前 Controller 切片测试已建立基线
- Prompt 约束的局限是 LLM + RAG 架构的普遍问题，不是这个项目特有的

这个项目适合展示的是：如何从零搭一个端到端的 RAG 系统、如何处理多源异构数据、如何在 Spring AI 框架下管理向量召回和对话记忆，以及如何在不依赖外部服务的情况下对 Web 层做可靠的自动化测试。
