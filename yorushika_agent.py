import os
import yt_dlp
import requests
from datetime import datetime

# 你的 Spring Boot 3.x (RuoYi 模块) 知识库入库接口（勿改成 B 站/YouTube 等视频链接）
INGEST_API_URL = "http://localhost:8080/api/v1/kb/ingest"

# 项目根目录的 cookies.txt（Netscape 格式），B 站 cookie 仅对 bilibili.com 链接有效
COOKIE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cookies.txt")


def fetch_yorushika_lore(video_url: str) -> dict:
    """
    使用 yt-dlp 提取视频元数据和简介。
    根目录 cookies.txt 为 B 站 Cookie 时，仅对 bilibili.com 链接生效；YouTube 需单独导出 YouTube 的 cookie。
    """
    print(f"[{datetime.now().time()}] 正在解析节点: {video_url} ...")
    ydl_opts = {
        "quiet": True,
        "skip_download": True,
        "writesubtitles": True,
        "subtitleslangs": ["ja", "zh-Hans", "zh"],
        "extractor_args": {"youtube": {"player_client": ["web", "android"]}},
        "socket_timeout": 30,
    }
    if os.path.isfile(COOKIE_FILE):
        ydl_opts["cookiefile"] = COOKIE_FILE
    
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info_dict = ydl.extract_info(video_url, download=False)
            return {
                "title": info_dict.get('title', 'Unknown Title'),
                "description": info_dict.get('description', ''),
                "uploader": info_dict.get('uploader', 'n-buna')
            }
    except yt_dlp.utils.DownloadError as e:
        print(f"[{datetime.now().time()}] yt-dlp 抓取失败 (YouTube 可能要求登录/验证): {e}")
        raise
    except Exception as e:
        print(f"[{datetime.now().time()}] 解析异常: {e}")
        raise

def ingest_to_knowledge_base(lore_data: dict) -> bool:
    """
    将清洗后的数据通过 HTTP POST 喂给 Spring AI 知识库。
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
        print(f"✅ 降维打击成功！《{lore_data['title']}》语料已向量化入库。")
        return True
    except requests.exceptions.RequestException as e:
        print(f"❌ 入库失败，请检查 Spring Boot 后端是否启动，或是否存在跨域/拦截器阻挡: {e}")
        return False
    except (ValueError, KeyError) as e:
        print(f"❌ 入库响应解析异常: {e}")
        return False

if __name__ == "__main__":
    # 支持 YouTube 与 B 站；cookies.txt 为 B 站 Cookie 时仅对 bilibili.com 生效
    target_urls = [
        # B 站（使用根目录 cookies.txt 中的 B 站登录态）；要抓新视频请只改这里，不要改上面的 INGEST_API_URL
        "https://www.bilibili.com/video/BV1oz4BzWEtu",   # 你换的新链接（可去掉 ?share_source= 等参数）
        # YouTube（需能直连或自备 YouTube 用 cookie）
        # "https://www.youtube.com/watch?v=ENcnYh79dUY",
        # "https://www.youtube.com/watch?v=siSFNM_nN00",
    ]
    
    print("🚀 启动 Antigravity 自动化语料收割机制...")
    for url in target_urls:
        lore = fetch_yorushika_lore(url)
        if lore['description']:
            ingest_to_knowledge_base(lore)
        else:
            print(f"⚠️ 警告: 未抓取到 {lore['title']} 的有效文本内容，跳过。")