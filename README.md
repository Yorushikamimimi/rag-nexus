<div align="center">

# RAG Nexus

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F?style=flat-square&logo=springboot)](https://spring.io/projects/spring-boot)

[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-6DB33F?style=flat-square&logo=spring)](https://docs.spring.io/spring-ai/reference/)

[![Vue](https://img.shields.io/badge/Vue-3.5-4FC08D?style=flat-square&logo=vue.js)](https://vuejs.org/)

[![pgvector](https://img.shields.io/badge/pgvector-HNSW-336791?style=flat-square&logo=postgresql)](https://github.com/pgvector/pgvector)

[![Tests](https://img.shields.io/badge/Tests-15%20backend%20tests-blue?style=flat-square&logo=junit5)](src/test/java/com/ragnexus/kb)

**基于 Spring AI 与 pgvector 的文本知识库 RAG 原型**

将文本资料切片并存入向量库，再检索相关内容生成回答。适合本地验证基础链路，尚未达到生产使用标准。

</div>

## 界面

![初始对话界面](docs/screenshots/homepage.png)

![本地合成资料的知识库切片预览](docs/screenshots/kb-panel.png)

截图展示界面状态，不作为完整链路的验收记录。

## 能力与边界

- 文件解析使用 Apache Tika；此前有限的本地验证覆盖 TXT、MD、DOCX 和有文本层的 PDF。旧 DOC 尚未验证。扫描件 OCR 未作为受支持能力验收；Tika 3.1 在宿主机存在 Tesseract 时可能尝试 OCR，Docker 镜像没有安装 Tesseract。

- 文本经 `TokenTextSplitter` 分块、Embedding 模型向量化后写入 PostgreSQL + pgvector。问答按向量相似度检索并调用聊天模型；同步接口返回来源，SSE 接口输出流式文本。

- 检索尚未配置相似度阈值，低相关度结果也可能进入模型上下文。请核对回答所附来源。

- 会话记忆保存在服务端内存中，窗口最多 10 条消息，后端重启后清空；前端没有多会话管理。

- 知识库页面显示已入库的文本切片，预览有条数和字符上限；它不是原始文件预览，也不能可靠还原原文顺序。

- 当前没有 API 鉴权，仅建议在本地或可信网络使用。

## 本地开发

需要 JDK 17+、Maven 3.8+、Node.js 18+、Docker 和正在运行的 Ollama。以下是本地配置示例，模型需先在 Ollama 中下载；本轮没有在干净机器上重跑启动流程。

1. 启动本地数据库并创建 `vector` 扩展。示例口令仅用于本机，请勿直接用于公网服务：

```bash
docker run -d --name rag-nexus-pg -e POSTGRES_PASSWORD=postgres \
  -p 5433:5432 pgvector/pgvector:pg16
docker exec rag-nexus-pg psql -U postgres -c 'CREATE EXTENSION IF NOT EXISTS vector;'
```

2. 下载本地模型，并创建被 `.gitignore` 忽略的 `src/main/resources/application-local.yml`。聊天与 embedding 模型需要分别配置；使用 `nomic-embed-text` 时，向量维度为 768。

```bash
ollama pull qwen2.5:7b
ollama pull nomic-embed-text
```

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/postgres
    username: postgres
    password: postgres
  ai:
    openai:
      base-url: http://localhost:11434
      api-key: ollama
      chat:
        options:
          model: qwen2.5:7b
      embedding:
        options:
          model: nomic-embed-text
    vectorstore:
      pgvector:
        dimensions: 768
```

3. 分别启动后端和前端：

```bash
mvn spring-boot:run
```

```bash
cd rag-nexus-web
npm install
npm run dev
```

前端默认地址为 <http://localhost:5173>，后端 Swagger UI 为 <http://localhost:8080/swagger-ui.html>。完整 Compose、干净机器首次启动和生产部署均未验收。

## Docker Compose 配置

仓库的 `docker-compose.yml` 定义三个服务：PostgreSQL + pgvector、Spring Boot 后端和 Vue/Nginx 前端。后端启用 `docker` profile，默认配置 DashScope 的 `qwen-plus`、`text-embedding-v2` 和 1536 维向量，要求宿主机提供 `AI_API_KEY`。这与上面的本地 Ollama 示例是两套配置。以下命令描述仓库配置；整套 Compose 启动本轮未验收：

```bash
export AI_API_KEY='YOUR_MODEL_API_KEY'
docker compose up -d --build
```

## 验证记录

- 本轮本地 Maven 测试：15 项通过（不代表真实数据库、模型服务、完整 RAG 流程或浏览器端到端验收）。

- 曾进行一次本地 Ollama + 临时 pgvector 浏览器试跑，使用合成 TXT/MD 资料并观察上传、切片预览和问答。它不代表 Compose 默认配置、其他文件格式、干净机器或部署环境已通过。

- 测试和本地试跑未覆盖真实用户数据、扫描件 OCR、完整 Compose 启动或生产环境。

## 示例资料

以下为虚构的本地试用资料：

- [社区河岸清洁日（Markdown）](examples/test-docs/社区河岸清洁日-2042-04-18.md)

- [城市种子交换会（TXT）](examples/test-docs/城市种子交换会-2042-05-22.txt)

- [布艺修补工作坊（Markdown）](examples/test-docs/布艺修补工作坊-2042-06-09.md)

- [上传说明与提问示例](examples/test-docs/README.md)

## API

| 方法 | 路径 | 用途 |
|------|------|------|
| `POST` | `/api/v1/kb/ingest` | 文本切片入库 |
| `POST` | `/api/v1/kb/upload` | 文件解析并入库 |
| `POST` | `/api/v1/kb/chat` | 同步问答 |
| `POST` | `/api/v1/kb/chat/stream` | SSE 流式问答 |
| `GET` | `/api/v1/kb/stats` | 知识库统计 |
| `GET` | `/api/v1/kb/chunks?docName=...` | 查询已入库切片预览 |
