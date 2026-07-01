# 启动指南（STARTUP）

> 本文档是 RAG-Nexus 本地启动的**单一入口**，记录已验证可用的完整命令与常见坑。
> 日常启动直接看「日常启动」一节；首次启动或换机器看「首次启动」。

---

## 端口一览

| 服务 | 端口 | 地址 | 启动方式 |
|------|------|------|----------|
| 前端 Vue (Vite) | **5173** | http://localhost:5173 | `npm run dev` |
| 后端 Spring Boot | **8080** | http://localhost:8080 | `mvn spring-boot:run` |
| 爬虫 FastAPI | **8000** | http://localhost:8000/docs | `uvicorn scraper_service:app --port 8000` |
| 数据库 pgvector | **5433** | localhost:5433 | docker 容器 `rag-nexus-pg` |

浏览器入口：**http://localhost:5173**
Swagger：http://localhost:8080/swagger-ui.html

---

## 前置条件

- Docker（跑 pgvector）
- JDK 17+（实测 JDK 21 可用）、Maven 3.8+
- Node.js 18+、npm
- Python 3.10+
- `src/main/resources/application-local.yml`（`.gitignore` 已忽略，**本机已配置好 DashScope key**；新 clone 需按 README 创建）

---

## 首次启动（全新环境）

### 1. 启动 pgvector 数据库（端口必须是 5433，与 `application-local.yml` 一致）

```bash
docker run -d \
  --name rag-nexus-pg \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=postgres \
  -p 5433:5432 \
  pgvector/pgvector:pg16

docker exec rag-nexus-pg psql -U postgres -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

> ⚠️ 端口必须映射成 `5433:5432`。`application-local.yml` 的 datasource 写死 `localhost:5433`，映射成 5432 后端会连接拒绝。

### 2. 装依赖

```bash
# Python 爬虫依赖
pip3 install -r requirements-scraper.txt

# 前端依赖（Mac 首次必做，见下方「常见坑」）
cd rag-nexus-web
rm -rf node_modules package-lock.json   # 清掉可能被 Windows 污染的 lock + node_modules
npm install
cd ..
```

### 3. 分终端启动三个服务

```bash
# 终端 1：爬虫
cd scripts && uvicorn scraper_service:app --host 0.0.0.0 --port 8000

# 终端 2：后端
mvn spring-boot:run

# 终端 3：前端
cd rag-nexus-web && npm run dev
```

后端首次启动会下载 Maven 依赖（约 1G，几分钟），看到 `Started RagNexusApplication` 即就绪。

---

## 日常启动（容器已存在）

```bash
# 1. 启数据库（容器已创建过，别再 docker run，否则丢数据）
docker start rag-nexus-pg

# 2. 三个终端分别起（同上）
cd scripts && uvicorn scraper_service:app --host 0.0.0.0 --port 8000
mvn spring-boot:run
cd rag-nexus-web && npm run dev
```

---

## 停止

```bash
# 停三个服务：各自终端 Ctrl+C

# 停数据库
docker stop rag-nexus-pg
```

---

## 验证启动成功

```bash
# 后端统计接口（应返回 {"code":200,...,"totalChunks":0,...}）
curl -s http://localhost:8080/api/v1/kb/stats

# 爬虫文档
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/docs   # 200

# 前端
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5173/        # 200
```

四条全部通过 = 启动完成。

---

## 常见坑

### 1. 后端报 `Connection to localhost:5433 refused`

pgvector 容器没起，或端口映射成了 5432。
```bash
docker ps | grep rag-nexus-pg          # 看是否在跑
docker port rag-nexus-pg                # 必须是 5432/tcp -> 0.0.0.0:5433
```
端口不对就 `docker rm -f rag-nexus-pg` 后按「首次启动」重建（容器内无业务数据，无损）。

### 2. 前端报 `vite: Permission denied` 或 `Cannot find module @rollup/rollup-darwin-arm64`

`node_modules` 是从 Windows 机器提交的（历史遗留），缺 macOS arm64 原生绑定 + 执行位丢失。
```bash
cd rag-nexus-web
rm -rf node_modules package-lock.json
npm install
```

### 3. 5173 端口被占（非 vite 进程）

```bash
lsof -nP -iTCP:5173 -sTCP:LISTEN       # 查谁占了
kill <PID>                             # 杀掉遗留进程（常见是 python -m http.server）
```

### 4. 向量 embedding 报 404

`application-local.yml` 的 `base-url` 末尾**不能带 `/v1`**（Spring AI 自动拼接，否则 `/v1/v1/embeddings` 404）：
```
base-url: https://dashscope.aliyuncs.com/compatible-mode   # 正确
```

---

## 数据库常用命令

```bash
# 查看 chunk 总数
docker exec rag-nexus-pg psql -U postgres -d postgres -c "SELECT COUNT(*) FROM vector_store;"

# 按文档统计
docker exec rag-nexus-pg psql -U postgres -d postgres -c \
  "SELECT metadata->>'docName' AS doc, COUNT(*) FROM vector_store GROUP BY 1 ORDER BY 2 DESC;"
```
