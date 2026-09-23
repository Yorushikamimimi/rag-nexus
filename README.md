<div align="center">

# RAG-Nexus

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-6DB33F?style=flat-square&logo=spring)](https://docs.spring.io/spring-ai/reference/)
[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D?style=flat-square&logo=vue.js)](https://vuejs.org/)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.115+-009688?style=flat-square&logo=fastapi)](https://fastapi.tiangolo.com/)
[![pgvector](https://img.shields.io/badge/pgvector-HNSW-336791?style=flat-square&logo=postgresql)](https://github.com/pgvector/pgvector)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)
[![Tests](https://img.shields.io/badge/Tests-6%20controller%20tests-blue?style=flat-square&logo=junit5)](src/test/java/com/ragnexus/kb/controller/KbControllerTest.java)

**基于 Spring AI + pgvector 的全栈 RAG 知识库问答系统**

提供文件、纯文本和视频 URL 三种入库入口，以及同步 / SSE 问答接口和 Vue 3 前端。当前文件选择器允许 TXT / MD / PDF / DOC / DOCX；视频 URL 流程通过 yt-dlp 读取元数据，不下载视频或导入字幕。仓库提供 Docker Compose 编排配置，本轮未启动整套 Compose。

</div>

---

## 项目定位

这是一个**工程原型项目**，完整实现了 RAG（检索增强生成）的核心链路，包含向量化存储、相似度召回、防幻觉 Prompt 设计和 SSE 流式输出。项目侧重工程实现与链路完整性，不具备生产级的权限管理、监控体系和高可用设计。

适合用于：个人知识库搭建、技术探索、RAG 实现学习参考。

> ⚠️ **安全警告**：本项目**所有 API 完全开放，无任何认证 / 鉴权 / 限流**，且全局异常处理已脱敏但仍非生产级。
> 仅限**本地或可信内网**运行。若需公网访问，请自行在前面加一层反向代理鉴权（nginx basic auth / API gateway / OAuth），
> 切勿将 8080 端口直接暴露到公网，否则任何人都能消耗你的 LLM 配额或篡改知识库。

---

## 核心功能

### 已实现

| 功能 | 说明 |
|------|------|
| **文本注入** | 接收原始文本，按 Token 分块后向量化写入 pgvector |
| **文件上传** | 前端选择器允许 TXT / MD / PDF / DOC / DOCX；后端使用 Apache Tika 提取文本后分块，无可索引切片时返回 HTTP 400 且明确说明未写入新向量、已有向量保持不变。本轮验证 TXT / MD / DOCX / 文本层 PDF；旧 DOC 未验证，PPT 不在当前选择器中 |
| **URL 注入** | 调用 FastAPI 爬虫服务，通过 yt-dlp 读取 B 站 / YouTube 的标题、简介和上传者元数据；仅接受 README 下方列出的 Bilibili / YouTube 主机。当前不下载视频或导入字幕；本轮仅验证一条公开 YouTube 链接 |
| **RAG 问答** | 向量相似度召回 Top-K 切片，拼装 System Prompt 后调用 LLM 生成回答 |
| **SSE 流式问答** | 基于 `Flux<String>` 的 Server-Sent Events，前端实时渲染，支持手动中断 |
| **多轮对话记忆** | `MessageWindowChatMemory` 滑动窗口，每个 sessionId 保留最近 10 轮上下文 |
| **防幻觉约束** | System Prompt 明确限定"仅依据检索片段作答"；检测到异常回答时降级为直接引用证据片段 |
| **知识库统计** | 查询总 chunk 数、总文档数及各文档 chunk 分布 |
| **来源溯源** | 每条 chunk 写入 `docName / author / sourceType` metadata，问答回包携带 `citations` |
| **Vue 3 前端** | 暗色主题，含文档管理面板、流式对话、Markdown 渲染 |
| **Docker Compose** | 4 服务一键编排（pgvector / Python 爬虫 / Spring Boot / Vue+Nginx） |

> **URL 抓取边界：** 入站链接仅接受 Bilibili 的 `bilibili.com`、`www.bilibili.com`、`m.bilibili.com`、`b23.tv`，以及 YouTube 的 `youtube.com`、`www.youtube.com`、`m.youtube.com`、`youtu.be`。该校验只检查初始主机；yt-dlp 可能跟随重定向或发起 extractor 后续请求，因此白名单不能单独构成完整 SSRF 防护，部署仍需限制网络出口。

> **错误状态边界：** scraper 拒绝请求时可在响应体返回业务码 `code=400`；Spring Boot URL 入库流程会包装非 200 业务码，当前对外 HTTP 状态可能为 500，后续需统一错误映射。

### 暂未实现

| 功能 | 说明 |
|------|------|
| OCR | 未接入 OCR；无文本层的扫描件 / 图片 PDF 不在当前验证范围内 |
| 权限与认证 | 所有 API 当前完全开放，无 Spring Security |
| 持久化会话历史 | 会话记忆为内存级别，后端重启后丢失 |
| 监控与可观测性 | 无 Prometheus / 链路追踪等配置 |

### 测试覆盖

仓库包含 7 个 Controller 层 `@WebMvcTest` 切片测试和 2 个入库服务单元测试，通过 Mockito 隔离后端服务依赖，覆盖请求校验、无可索引文本拒绝、响应包装及异常响应。2026-09-23 在 JDK 21 下的隔离源码副本执行 `mvn -o -Dtest=KbControllerTest,KbIngestionServiceTest test`：9 个测试通过（0 失败、0 错误、0 跳过）。这些测试没有覆盖真实数据库、LLM、爬虫服务或完整 RAG 链路。

### 本轮真实服务验证（2026-09-23）

- 在临时 PostgreSQL/pgvector、Spring Boot、实际 yt-dlp 爬虫服务和本地 Ollama 模型上，以合成资料验证上传与问答：TXT、MD、DOCX、有效文本层 PDF 各写入 1 个切片；RAG 问答返回合成标记并带来源。
- 入口安全加固前的隔离联调记录：一条公开 YouTube URL 经爬虫和 `/api/v1/kb/ingest/url` 入库，生成 2 个切片；抓取配置跳过视频下载，当前代码只将标题与简介作为内容，来源元数据另存上传者。加固后重新请求外网未成功，原因未确认，因此尚无加固后外网抓取与入库通过的验证。
- 前端类型检查与生产构建通过（`vue-tsc -b --pretty false`、`vite build`）；本轮未运行 Playwright。仓库当前未找到 Playwright 配置或测试文件，浏览器直连真实服务也未验证。
- 未验证：整套 Docker Compose、干净机器首次启动、旧 DOC / PPT 文件、扫描件 OCR、托管模型服务。此次没有下载模型，也没有调用 OpenAI / DashScope。

---

## 技术栈

| 层 | 技术 |
|----|------|
| **后端** | Spring Boot 3.4 / Java 17 / Spring AI 1.0 / Lombok |
| **前端** | Vue 3.5 / TypeScript / TailwindCSS 3.4 / Vite 5.4 |
| **AI 框架** | Spring AI（ChatClient / VectorStore / MessageChatMemoryAdvisor） |
| **LLM / Embedding** | 阿里云 DashScope `qwen-plus` + `text-embedding-v2`（1536 维）；兼容 OpenAI API |
| **向量存储** | PostgreSQL 16 + pgvector（HNSW 索引 + 余弦距离） |
| **文档解析** | Apache Tika（spring-ai-tika-document-reader） |
| **爬虫微服务** | Python 3.10 / FastAPI 0.115 / yt-dlp |
| **部署** | Docker Compose / 多阶段 Dockerfile（Java + Python + Vue/Nginx） |
| **API 文档** | Swagger UI（springdoc-openapi 2.6） |

---

## 系统架构

```mermaid
flowchart TB
    subgraph Client["浏览器"]
        User["用户"]
    end

    subgraph Frontend["前端（:80 / :5173 dev）"]
        Vue["Vue 3 SPA\nVite → Nginx"]
    end

    subgraph Backend["后端（:8080）"]
        Controller["KbController\n/api/v1/kb/*"]
        Ingestion["KbIngestionService\n文本 / 文件 / URL 注入"]
        Chat["RagChatService\nRAG 召回 + Prompt 拼装"]
        Memory["ChatMemory\n滑动窗口 10 轮"]
    end

    subgraph Infra["基础设施"]
        PG[("PostgreSQL 16\n+ pgvector\nHNSW 索引")]
        Scraper["FastAPI Scraper\n(:8000)\nyt-dlp"]
        LLM["阿里云 DashScope\nqwen-plus\ntext-embedding-v2"]
    end

    User -->|HTTP| Vue
    Vue -->|"/api/* 代理"| Controller
    Controller --> Ingestion
    Controller --> Chat
    Chat --> Memory
    Ingestion -->|"TokenTextSplitter\n→ embedding → add()"| PG
    Ingestion -->|"POST /api/v1/scraper/extract"| Scraper
    Scraper -->|"yt-dlp 抓取\ntitle + description"| LLM
    Chat -->|"similaritySearch(topK)"| PG
    Chat -->|"ChatClient.prompt()\n.stream()"| LLM
    Chat -.->|"SSE Flux<String>"| Vue
```

---

## 处理链路

### 内容注入链路

```
[方式 A] 纯文本 POST /api/v1/kb/ingest
    └─→ TokenTextSplitter (chunkSize=500, 可自定义)
         └─→ 写入 metadata: {docName, author, sourceType}
              └─→ EmbeddingModel (text-embedding-v2, 1536 维)
                   └─→ pgvector vector_store (HNSW + 余弦距离)

[方式 B] 文件上传 POST /api/v1/kb/upload
    └─→ Apache Tika 解析文本层（本轮验证：PDF / DOCX / TXT / MD；DOC 未验证，PPT 未验证且不在前端选择器中）
         └─→ 同上分块 → embedding → pgvector

[方式 C] URL POST /api/v1/kb/ingest/url
    └─→ HTTP POST → FastAPI :8000/api/v1/scraper/extract
         └─→ yt-dlp 提取 title + description + uploader
              └─→ 同上分块 → embedding → pgvector
```

### 问答链路

```
用户提问 POST /api/v1/kb/chat (同步) 或 /chat/stream (SSE)
    └─→ vectorStore.similaritySearch(query, topK)    ← 向量召回
         └─→ buildRagSystemPrompt(召回片段)           ← Prompt 拼装
              约束：仅依据检索片段作答，不足以判断则明确说明
              └─→ ChatClient.prompt()
                   + MessageChatMemoryAdvisor         ← 注入多轮记忆
                   + temperature / topK (请求级可配)
                   └─→ qwen-plus 推理
                        ├─→ 同步返回 KbChatResponse {answer, citations}
                        └─→ SSE 流式 Flux<String> → 前端 ReadableStream
                             └─→ normalizeGarbledAnswer() 乱码兜底
```

---

## 工程亮点

**1. Spring AI 适配阿里云 DashScope**

Spring AI 默认对接 OpenAI，项目通过 `AiConfig.java` 显式构建 `OpenAiChatModel` 并注入兼容的 `base-url`，使用 `@ConditionalOnMissingBean` 防止与自动配置冲突。`base-url` 不含 `/v1` 后缀（Spring AI 自动拼接，否则出现 `/v1/v1/` 404）。

**2. 防幻觉 Prompt 设计 + 运行时降级**

`buildRagSystemPrompt()` 明确约束 LLM"仅依据检索片段回答"。检测到异常回答（如乱码占位）时，`isLikelyGarbled()` 触发降级，`buildEvidenceFallback()` 直接将召回片段原文返回，保证回答始终有依据可查。

**3. SSE 流式响应全链路打通**

`KbController` 返回 `Flux<String>`（`text/event-stream`），经 Nginx 反向代理（`proxy_buffering off`）到达前端；前端使用原生 `fetch` + `ReadableStream` + `AbortController` 实现打字机效果与中断按钮，全程不依赖第三方 SSE 库。

**4. 三源统一注入管道**

文本（直接分块）、文件（Tika 解析）、URL（yt-dlp 抓取）三种来源最终走同一条 `TokenTextSplitter → metadata 写入 → VectorStore.add()` 管道，每条 chunk 携带 `docName / author / sourceType` 用于问答时溯源。

**5. pgvector HNSW 索引选型**

显式配置 `index-type: HNSW`、`distance-type: COSINE_DISTANCE`、`dimensions: 1536`，与 `text-embedding-v2` 维度一致，由 Spring AI 首次启动时自动建表建索引（`initialize-schema: true`），不依赖手动 DDL。

**6. 多服务 Docker Compose 编排**

4 个异构服务（Java / Python / Vue+Nginx / pgvector）通过一份 `docker-compose.yml` 编排，postgres 配置健康检查，java-backend 设置 `depends_on: condition: service_healthy`，确保数据库就绪后后端再启动；三个多阶段 Dockerfile 生产镜像分别为 JRE 17 / python:3.10-slim / nginx:alpine。

---

## 本地启动

### 方式一：Docker Compose（推荐）

**前置条件：** Docker Compose、阿里云 DashScope API Key。当前 Compose 启用 `docker` profile，默认配置为 DashScope Compatible Mode、`qwen-plus` 和 `text-embedding-v2`；本启动示例不代表已验证其他 OpenAI-compatible 服务商的接入。

```bash
git clone https://github.com/Yorushikamimimi/rag-nexus.git
cd rag-nexus

# docker-compose.yml 要求宿主机提供 AI_API_KEY
export AI_API_KEY="YOUR_DASHSCOPE_API_KEY"

docker-compose up -d --build
```

| 服务 | 地址 |
|------|------|
| 前端 | http://localhost |
| 后端 API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Python 爬虫服务 | http://localhost:8000/docs |

### 方式二：本地开发（逐服务启动）

**前置条件：** JDK 17+、Maven 3.8+、Node.js 18+、Python 3.10+、Docker

**第一步：启动 pgvector 数据库**

```bash
docker run -d --name rag-nexus-pg \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=postgres \
  -p 5433:5432 \
  pgvector/pgvector:pg16

docker exec rag-nexus-pg psql -U postgres -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

> 之后用 `docker start rag-nexus-pg` 启动，不要重复 `docker run`（会丢失数据）。

**第二步：创建 `src/main/resources/application-local.yml`**（已被 `.gitignore` 忽略，需手动创建）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/postgres
    username: postgres
    password: postgres
  ai:
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode  # 末尾不加 /v1
      api-key: <你的 DashScope API Key>
      chat:
        options:
          model: qwen-plus
          temperature: 0.3
      embedding:
        options:
          model: text-embedding-v2
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
        initialize-schema: true
```

**第三步：分终端启动各服务**

```bash
# 终端 1：Python 爬虫服务
pip install -r requirements-scraper.txt
cd scripts && uvicorn scraper_service:app --host 0.0.0.0 --port 8000 --reload

# 终端 2：Spring Boot 后端
mvn spring-boot:run

# 终端 3：Vue 前端
cd rag-nexus-web && npm install && npm run dev
```

前端访问：http://localhost:5173

---

## 关键接口

> 完整接口文档见 Swagger UI：`http://localhost:8080/swagger-ui.html`

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/kb/ingest` | 文本分块入库 |
| `POST` | `/api/v1/kb/ingest/url` | URL 抓取入库（yt-dlp） |
| `POST` | `/api/v1/kb/upload` | 文件上传入库（Tika 解析） |
| `POST` | `/api/v1/kb/chat` | RAG 同步问答 |
| `POST` | `/api/v1/kb/chat/stream` | RAG SSE 流式问答 |
| `GET`  | `/api/v1/kb/stats` | 知识库统计信息 |

**问答请求示例（`/chat`）：**

```json
{
  "query": "这个项目的核心技术是什么？",
  "sessionId": "user-abc-123",
  "topK": 5,
  "temperature": 0.3
}
```

**问答响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "answer": "根据检索片段，核心技术包括...",
    "citations": ["技术文档_v1.pdf", "架构说明.txt"]
  }
}
```

---

## 目录结构

```
rag-nexus/
├── src/                              # Spring Boot 后端
│   └── main/java/com/ragnexus/
│       ├── config/AiConfig.java      # ChatModel / ChatClient Bean 显式注册
│       └── kb/
│           ├── controller/           # REST 端点
│           ├── service/              # 注入 / 查询 / RAG 对话
│           ├── domain/dto/           # 请求响应 DTO
│           ├── common/Result.java    # 统一响应封装
│           └── config/              # 会话记忆配置
├── rag-nexus-web/                    # Vue 3 前端（含 Dockerfile + nginx.conf）
├── scripts/
│   ├── scraper_service.py            # FastAPI 爬虫微服务（yt-dlp）
│   └── batch_ingest_agent.py         # 批量视频语料灌入脚本（本地运行）
├── docs/
│   ├── DECISIONS.md                  # 架构决策记录
│   └── archive/                      # 归档文件（数据迁移备份等）
├── docker-compose.yml                # 4 服务编排
├── Dockerfile                        # Spring Boot 多阶段构建
├── Dockerfile.python                 # Python 爬虫服务镜像
├── init-pgvector.sql                 # pgvector 扩展初始化
└── requirements-scraper.txt         # Python 依赖
```

---

## 已知问题

| 问题 | 说明 |
|------|------|
| `overlap` 参数无效 | `KbIngestRequest` 定义了 `overlap` 字段，但 `TokenTextSplitter` 当前未读取，分块时不生效 |
| 扫描件 PDF 无法解析 | Apache Tika 仅解析文本层，图片型 PDF 返回空内容 |
| 会话记忆重启丢失 | `InMemoryChatMemoryRepository` 不持久化，后端重启后所有对话历史消失 |
| 中文乱码兜底 | 特定场景下 LLM 回答含乱码，当前通过 `normalizeGarbledAnswer()` 运行时清洗，根本原因仍在排查 |

> 系统完整的失败模式分析（含代码级根因与改进方向）见 → [docs/failure-cases.md](docs/failure-cases.md)

---

## 项目截图

**首页 / 对话界面**

![首页截图](docs/screenshots/homepage.png)

**文档管理界面**

![文档管理界面](docs/screenshots/kb-panel.png)

**流式问答界面**

![流式问答界面](docs/screenshots/streaming-chat.png)

**Docker Compose 启动日志**

<!-- TODO: 替换为实际截图
![Docker Compose 启动](docs/screenshots/docker-compose-up.png)
-->
> 待补充：`docker-compose up --build` 执行完成后的终端截图，展示 4 个服务均健康启动。

---

## 后续优化方向

- [ ] 接入 OCR（如 Tesseract）支持扫描件 PDF
- [ ] 会话历史持久化（Redis 或数据库存储）
- [ ] 添加 Spring Security 基础权限控制
- [ ] `overlap` 参数接入 `TokenTextSplitter`，修复分块重叠逻辑
- [x] Controller 切片测试（`@WebMvcTest` + Mockito，7 用例；另有 2 个入库服务单元测试）
- [ ] 支持知识库文档删除接口
- [ ] RAG 召回效果评估（Recall@K 等指标）

---

## License

[MIT](LICENSE)
