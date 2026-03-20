# Decisions Record

## D1. 采用"两阶段启动"而不是一次性全容器启动
- Decision:
  - 先基础设施（DB），再应用层（Python/Backend/Frontend）。
- Why:
  - 更快定位失败点，降低调试成本。

## D2. 知识库可见性优先做"统计面板"
- Decision:
  - 先做 `stats` 接口 + 前端展示，暂不做完整检索预览。
- Why:
  - 以最小改动先解决"库里有没有数据"的核心痛点。

## D3. 流式中断放在前端完成
- Decision:
  - 使用 `AbortController` 中断 `fetch` 流。
- Why:
  - 实现稳定、改动小、对后端侵入低。

## D4. 乱码修复采用"无损迁移"优先
- Decision:
  - 使用容器内 dump 文件 + `docker cp` + 容器内导入，避免 PowerShell 文本管道转码。
- Why:
  - 文本管道可能破坏多字节字符，二进制链路更安全。

## D5. 问答乱码采用"后端兜底 + 编码配置"双保险
- Decision:
  - 清理 RAG 系统提示词乱码；
  - 增加 `server.servlet.encoding` UTF-8 强制配置；
  - 保留回答清洗/兜底，避免用户看到问号占位。
- Why:
  - 问题链路不一定单点，双层防护更稳。
