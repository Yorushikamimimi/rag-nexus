"""
批量视频元数据语料灌入脚本（本地运行）。

复用 scraper_service 的 yt-dlp 抓取逻辑（_extract_video_metadata），避免重复实现。
运行方式：在 scripts/ 目录下 `python batch_ingest_agent.py`，需先启动 Spring Boot 后端。
"""
import requests
from datetime import datetime

# 复用微服务的抓取函数（cookies.txt 解析、超时、字幕配置都在那边维护）
from scraper_service import _extract_video_metadata

# Spring Boot 知识库入库接口
INGEST_API_URL = "http://localhost:8080/api/v1/kb/ingest"


def ingest_to_knowledge_base(lore_data: dict) -> bool:
    """
    将清洗后的数据通过 HTTP POST 喂给知识库 Ingestion API。
    同时校验 HTTP 200 与业务 code==200，避免后端返回 500 时仍被误判为成功。
    """
    payload = {
        "docName": f"{lore_data['title']}_Metadata.txt",
        "content": lore_data["description"],
        "chunkSize": 500,
        "overlap": 50,
    }
    print(f"[{datetime.now().time()}] 准备触发知识库 Ingestion API...")
    try:
        response = requests.post(
            INGEST_API_URL,
            json=payload,
            headers={"Content-Type": "application/json"},
            timeout=10,
        )
        response.raise_for_status()
        json_data = response.json()
        biz_code = json_data.get("code")
        if biz_code != 200:
            msg = json_data.get("message") or json_data.get("errorMessage") or str(json_data)
            print(f"❌ 入库失败（业务码 {biz_code}）: {msg}")
            return False
        print(f"✅ 入库成功！《{lore_data['title']}》语料已向量化入库。")
        return True
    except requests.exceptions.RequestException as e:
        print(f"❌ 入库失败，请检查 Spring Boot 后端是否启动: {e}")
        return False
    except (ValueError, KeyError) as e:
        print(f"❌ 入库响应解析异常: {e}")
        return False


if __name__ == "__main__":
    # 支持 YouTube 与 B 站
    # 如需 B 站登录态，请将 cookies.txt（Netscape 格式）放在 scripts/ 目录下
    target_urls = [
        # B 站示例（替换为目标视频链接）
        "https://www.bilibili.com/video/BV1oz4BzWEtu",
        # YouTube 示例（需能直连或自备 YouTube cookie）
        # "https://www.youtube.com/watch?v=ENcnYh79dUY",
    ]

    print("🚀 启动批量视频元数据语料灌入...")
    for url in target_urls:
        try:
            data = _extract_video_metadata(url)
            lore = {"title": data.title, "description": data.description, "uploader": data.uploader}
        except Exception as e:
            print(f"[{datetime.now().time()}] ❌ 抓取失败 {url}: {e}")
            continue

        if lore["description"]:
            ingest_to_knowledge_base(lore)
        else:
            print(f"⚠️ 警告: 未抓取到《{lore['title']}》的有效文本内容，跳过。")
