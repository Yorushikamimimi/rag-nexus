# WORKLOG — 审查问题全量修复

- **日期**：2026-07-01
- **分支**：`main`（领先 origin/main 1 个 commit：`a05d694` node_modules 清理）
- **本次改动状态**：工作区已改，**尚未 commit**（等用户确认）
- **审查来源**：上一轮全方位审查报告（3 Critical + 8 Important + 10 Minor = 21 项）

> 路径事件：会话期间发现 `/Users/yang/Workspace/cursor_projects/rag-nexus` 是断链空壳，
> **真实项目在 `/Users/yang/Workspace/SelfProject/cursor_projects/rag-nexus`**（`.git` 完好）。
> 本轮所有改动最终均落在真实路径并已逐一复审。空壳目录已清理。

---

## Critical

| # | 文件 | 改动 | 验证 |
|---|------|------|------|
| C1 | `application-local.yml` | 删除硬编码 API key default，改 `${AI_API_KEY:}`（空 default，强制 env） | 无 key 启动 → fail-fast `OpenAI API key must be set`，符合预期 |
| C2 | `rag-nexus-web/nginx.conf` | `location /api/` 补 `proxy_buffering off` / `proxy_http_version 1.1` / `X-Accel-Buffering no` / `proxy_read_timeout 300s` | grep 确认 |
| C3 | 新增 `application-docker.yml` + 改 `docker-compose.yml` | docker profile 文件（DashScope qwen-plus，全 env 不内联 key）；compose 取消注释 `SPRING_PROFILES_ACTIVE: docker` + `AI_API_KEY` 强制 env | `docker-compose config` 通过，profile/数据库名/key 注入正确 |

> **C1 用户动作（代码外）**：DashScope 控制台轮换 `sk-b919...`（该 key 在历史对话/旧 jar 中已暴露）。

## Important

| # | 文件 | 改动 | 验证 |
|---|------|------|------|
| I1 | `GlobalExceptionHandler.java` | 通用 Exception handler 不再回传 `ex.getMessage()`，固定 `服务器内部错误，请查看后端日志`；新增 `IllegalArgumentException → 400` | mvn test 通过 |
| I2 | `scraper_service.py` + `KbIngestUrlRequest.java` | 爬虫侧校验 http/https scheme；DTO 加 `@Pattern("^https?://.+")` | grep 确认 |
| I3 | `README.md` | 项目定位段加 ⚠️ 安全警告框（API 无认证，禁公网裸暴露） | grep 确认 |
| I4 | `App.vue` | `MarkdownIt({html:true})` → `html:false`，堵 LLM 输出 XSS | grep 确认 |
| I5 | `KbIngestionService.java` | 静态 `RestTemplate` 改为 connect 5s / read 30s 超时工厂 | grep 确认 |
| I6/I7 | `KbController.java` + `GlobalExceptionHandler.java` + `KbControllerTest.java` | 删除 Controller 每方法 try/catch，统一交 GlobalExceptionHandler；校验失败 400、Service 异常 500；测试 `stats_serviceThrows` 改期望 HTTP 5xx + 脱敏 message | mvn test 6/6 通过 |
| I8 | `AiConfig.java` | 删除手搓 `chatModel()` bean（与 starter 冗余），仅保留 `chatClient()` | **`mvn spring-boot:run` 实测 `Started RagNexusApplication in 4.1s`，无 ChatClient/ChatModel 装配错误，starter 正确提供 bean** |

## Minor

| # | 改动 | 验证 |
|---|------|------|
| M1 | `KbChatRequest.temperature` 加 `@DecimalMin(0.0)/@DecimalMax(2.0)` | grep + 编译通过 |
| M2 | `KbChatRequest.topK` / `KbIngestRequest.chunkSize,overlap` 去掉 `@NotNull`（保留 `@Positive`，null 时 service 默认值生效，客户端可省略字段） | grep 确认 |
| M3 | `batch_ingest_agent.py` 改为 import `scraper_service._extract_video_metadata`，去重 yt-dlp 逻辑 | grep 确认 |
| M4 | `KbIngestionService` 抽 `splitAndStore(sourceDocs, metadata, chunkSize)` 公共管道，三源复用 | 编译通过 |
| M5 | `KbControllerTest` `@MockBean` → `@MockitoBean`（Spring Boot 3.4+ 推荐） | mvn test 6/6 通过 |
| M6 | 删除项目根 `.m2repo/`（136M，本地 Maven 缓存，已 gitignore） | `ls .m2repo` → No such |
| M7 | `README.md` 目录结构删除不存在的 `docs/CHANGES.md` 死链 | grep 确认 |
| M8 | `docker-compose.yml` 数据库名 `ragnexus` → `postgres`，与 `application-local.yml` 对齐 | `docker-compose config` 确认 |
| M9 | `ingestFromFile` 去掉多余 `"source"` metadata key，三源 schema 对齐（docName/author/sourceType） | 含在 M4 重构内 |
| M10 | `docs/failure-cases.md` Case 6 补注：流式逐 chunk 清洗跨边界失效，**接受为已知限制不硬修** | grep 确认 |

---

## 验证汇总

| 项 | 命令 | 结果 |
|----|------|------|
| 单元测试 | `mvn test` | **Tests run: 6, Failures: 0, Errors: 0 — BUILD SUCCESS** |
| 启动装配（验 I8） | `AI_API_KEY=… mvn spring-boot:run` | **`Started RagNexusApplication in 4.124s`**，Tomcat 8080，HikariPool+PgVectorStore 就绪 |
| C1 fail-fast | 无 key 启动 | `OpenAI API key must be set`，无静默 fallback ✓ |
| compose 配置 | `AI_API_KEY=t docker-compose config` | 语法 OK，profile/db/key 正确 |

---

## 遗留 / 接受为限制

- **C1 key 轮换**：用户在 DashScope 控制台操作；代码侧已完成（删 default + 强制 env）。
- **I3 鉴权**：按用户决策只加 README 警告，未实装 Spring Security。
- **M10 流式乱码跨 chunk**：标注为已知限制（根因治理需累积完整回答后再清洗或引 RAGAS）。
- **failure-cases.md 已记 7 项**（overlap / OCR / 字幕 / 阈值 / 记忆持久化 / 测试覆盖等）不在本次范围。

## 下一步建议

1. 用户确认后单个干净 commit（Conventional Commits + Chinese）。
2. 轮换 DashScope key。
3. （可选）继续 failure-cases.md 的 7 项中优先级高的：`overlap` 接入 splitter（一行修复）、`similarityThreshold`。
