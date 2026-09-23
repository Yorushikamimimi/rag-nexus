<div align="center">

# RAG Nexus

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-6DB33F?style=flat-square&logo=spring)](https://docs.spring.io/spring-ai/reference/)
[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D?style=flat-square&logo=vue.js)](https://vuejs.org/)
[![pgvector](https://img.shields.io/badge/pgvector-HNSW-336791?style=flat-square&logo=postgresql)](https://github.com/pgvector/pgvector)
[![Tests](https://img.shields.io/badge/Tests-14%20backend%20tests-blue?style=flat-square&logo=junit5)](src/test/java/com/ragnexus/kb)

**基于 Spring AI 与 pgvector 的全栈文本知识库 RAG 原型**

接收文本资料并建立向量索引，通过检索结果生成带来源的回答。项目处于原型阶段，重点是可读的入库、检索和问答链路，不代表可直接用于生产。

</div>

## 界面截图

![初始对话界面](docs/screenshots/homepage.png)

_初始对话界面。_

![本地合成资料的知识库切片预览](docs/screenshots/kb-panel.png)

_本地合成资料的知识库切片预览。_

## 当前能力与边界

- **入库**：支持纯文本请求和文件上传。已有运行验证覆盖 TXT、MD、DOCX、有文本层的 PDF；旧 DOC 尚未验证。扫描件 PDF 的 OCR 未实现。
- **问答**：按向量相似度检索切片，再调用聊天模型生成回答；同步接口返回来源信息，SSE 接口逐段输出并可由前端中断。
- **前端**：Vue 页面提供文件上传、知识库统计、已入库切片预览和单页问答。切片预览有条数与字符上限，不是原始文件预览，也不能还原可靠的原文切片顺序。
- **会话**：每个页面实例生成一个 `sessionId`；服务端使用内存中的消息窗口，最多保存 10 条消息。后端重启会丢失会话记忆；页面没有多会话管理界面。
- **安全**：当前没有 API 鉴权。仅建议在本地或可信网络使用，不要将后端端口直接暴露到公网。

模型回答可能有误，请结合来源资料核对。自动化测试的覆盖边界见下方验证记录。

## 数据与问答链路

```text
文本请求 / 文件上传
  → 文本提取（文件使用 Apache Tika）
  → TokenTextSplitter 分块
  → EmbeddingModel 向量化
  → PostgreSQL + pgvector 存储

提问
  → pgvector 相似度检索
  → 组合检索片段与会话上下文
  → Spring AI ChatClient 生成回答
  → 同步接口返回回答与来源；SSE 接口当前输出流式文本
```

## 本地启动

**前置条件：** JDK 17+、Maven 3.8+、Node.js 18+、Docker，以及所选模型服务的配置。首次安装、干净机器启动和整套 Compose 尚未验收。

### 本地开发（逐服务）

先启动 PostgreSQL + pgvector。以下数据库口令是本机示例值，请按本机环境替换，不要复用于公网服务：

```bash
export RAG_NEXUS_DB_PASSWORD='replace-with-local-password'
docker run -d --name rag-nexus-pg \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD="$RAG_NEXUS_DB_PASSWORD" \
  -e POSTGRES_DB=postgres \
  -p 5433:5432 \
  pgvector/pgvector:pg16
```

数据库容器就绪后，创建 pgvector 扩展：

```bash
docker exec rag-nexus-pg psql -U postgres -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

创建被 `.gitignore` 忽略的 `src/main/resources/application-local.yml`，示例使用 DashScope Compatible Mode：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/postgres
    username: postgres
    password: ${RAG_NEXUS_DB_PASSWORD}
  ai:
    openai:
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      api-key: ${DASHSCOPE_API_KEY}
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

设置 `RAG_NEXUS_DB_PASSWORD` 和 `DASHSCOPE_API_KEY` 后，分别在两个终端启动：

```bash
mvn spring-boot:run
```

```bash
cd rag-nexus-web
npm install
npm run dev
```

前端地址：<http://localhost:5173>。以上步骤是仓库提供的开发说明，尚未在本轮以干净环境验证。

### Docker Compose 配置参考（未验证）

Compose 使用仓库的 `docker` profile，默认配置 DashScope Compatible Mode、`qwen-plus` 和 `text-embedding-v2`，要求宿主机提供 `AI_API_KEY`。此前的本地 Ollama 试跑使用独立临时配置，不是 Compose 默认配置，也不能证明托管模型或 Compose 可运行。

```bash
git clone https://github.com/Yorushikamimimi/rag-nexus.git
cd rag-nexus
export AI_API_KEY='YOUR_DASHSCOPE_API_KEY'
docker compose up -d --build
```

**整套 Compose 启动、干净机器首次启动及上述命令的当前可运行性均未验证；此处仅记录仓库配置。** 不要把占位值替换成真实密钥后提交到版本库。

## 验证记录

- 仓库包含 14 项后端测试代码：9 项 Controller 切片测试、2 项入库服务测试、1 项问答服务测试、2 项切片查询测试。
- 既有本地记录（2026-09-23）：Maven 测试 14 项通过；这是一轮本地结果，不是干净机器、真实数据库/LLM 或端到端验收。
- 既有一次本地 Ollama + 临时 pgvector 浏览器试跑使用合成 TXT 和 MD，观察了上传、切片预览和问答；该记录不证明 Compose 默认 DashScope 配置、其他文件格式或部署环境可用。
- 两张截图分别记录初始对话界面和合成 Markdown 资料的切片预览；它们不证明完整功能链路已通过。

测试代码不覆盖真实数据库、模型服务、完整 RAG 流程或浏览器端到端场景。整套 Compose、干净机器启动、扫描件 OCR、旧 DOC、生产部署和真实用户数据尚未验证。

## 示例资料

以下文件是虚构资料，可在本地上传后按示例问题试用：

- [社区河岸清洁日（Markdown）](examples/test-docs/社区河岸清洁日-2042-04-18.md)
- [城市种子交换会（TXT）](examples/test-docs/城市种子交换会-2042-05-22.txt)
- [布艺修补工作坊（Markdown）](examples/test-docs/布艺修补工作坊-2042-06-09.md)
- [上传说明与提问示例](examples/test-docs/README.md)

## 关键接口

| 方法 | 路径 | 用途 |
|------|------|------|
| `POST` | `/api/v1/kb/ingest` | 文本分块入库 |
| `POST` | `/api/v1/kb/upload` | 文件解析并入库 |
| `POST` | `/api/v1/kb/chat` | 同步问答 |
| `POST` | `/api/v1/kb/chat/stream` | SSE 流式问答 |
| `GET` | `/api/v1/kb/stats` | 知识库统计 |
| `GET` | `/api/v1/kb/chunks?docName=...` | 查询已入库切片预览 |

完整接口说明可在本地服务启动后通过 Swagger UI 查看：<http://localhost:8080/swagger-ui.html>。
