-- 确保 pgvector 扩展在 ragnexus 数据库中可用
-- 挂载到 /docker-entrypoint-initdb.d/ 时，PostgreSQL 会在首次启动时执行
CREATE EXTENSION IF NOT EXISTS vector;
