"""
视频元数据抓取微服务。
被动响应 RESTful API，仅负责 extract，不向 Spring Boot 推送。

部署说明：
  - Docker 运行（推荐）：Dockerfile.python 将本文件复制到 /app/scraper_service.py
    cookies.txt 通过 volume 挂载到 /app/cookies.txt（docker-compose.yml 中已注释示例）
  - 本地运行：在 scripts/ 目录下执行 uvicorn scraper_service:app --reload
    此时 cookies.txt 应放在 scripts/ 目录下，或使用绝对路径
"""
import ipaddress
import logging
import os
from typing import Any
from urllib.parse import urlsplit

import yt_dlp
from fastapi import FastAPI
from pydantic import BaseModel, Field

# Docker 内：本文件被复制到 /app/scraper_service.py，cookies.txt 通过 volume 挂载到 /app/
# 本地运行：cookies.txt 与本文件同目录（scripts/cookies.txt）
# 参考占位文件格式：docs/archive/cookies.example.txt
COOKIE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cookies.txt")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="RAG-Nexus Scraper Service", version="1.0.0")


SUPPORTED_VIDEO_HOSTS = frozenset(
    {
        "bilibili.com",
        "www.bilibili.com",
        "m.bilibili.com",
        "b23.tv",
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be",
    }
)


class UnsupportedVideoUrl(ValueError):
    """Raised when a URL is outside the scraper's supported host boundary."""


def _validate_video_url(video_url: str) -> str:
    """Return the normalized approved host, rejecting ambiguous or external URLs."""
    if not video_url or any(ord(char) <= 0x20 or ord(char) == 0x7F for char in video_url):
        raise UnsupportedVideoUrl("invalid URL")

    try:
        parsed = urlsplit(video_url)
        host = parsed.hostname
        port = parsed.port
    except ValueError as exc:
        raise UnsupportedVideoUrl("invalid URL") from exc

    if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc or not host:
        raise UnsupportedVideoUrl("unsupported URL")
    if parsed.username is not None or parsed.password is not None or "@" in parsed.netloc:
        raise UnsupportedVideoUrl("unsupported URL")
    if port is not None or parsed.netloc.endswith(":") or host.endswith("."):
        raise UnsupportedVideoUrl("unsupported URL")

    try:
        normalized_host = host.encode("idna").decode("ascii").lower()
    except UnicodeError as exc:
        raise UnsupportedVideoUrl("unsupported URL") from exc

    try:
        ipaddress.ip_address(normalized_host)
    except ValueError:
        pass
    else:
        raise UnsupportedVideoUrl("unsupported URL")

    if normalized_host not in SUPPORTED_VIDEO_HOSTS:
        raise UnsupportedVideoUrl("unsupported URL")
    return normalized_host


# ---------- Pydantic DTOs ----------


class ExtractRequest(BaseModel):
    """抓取请求体"""
    url: str = Field(..., description="视频链接，如 B 站 / YouTube")


class ExtractData(BaseModel):
    """抓取成功时的 data 字段"""
    title: str
    description: str
    uploader: str


class ApiResponse(BaseModel):
    """统一响应格式"""
    code: int
    message: str
    data: ExtractData | None = None


# ---------- 核心抓取逻辑 ----------


def _extract_video_metadata(video_url: str) -> ExtractData:
    """
    使用 yt-dlp 提取视频元数据和简介。
    cookies.txt 为 B 站 Cookie 时仅对 bilibili.com 生效；YouTube 需单独导出 cookie。
    """
    _validate_video_url(video_url)

    ydl_opts: dict[str, Any] = {
        "quiet": True,
        "skip_download": True,
        "writesubtitles": True,
        "subtitleslangs": ["ja", "zh-Hans", "zh"],
        "extractor_args": {"youtube": {"player_client": ["web", "android"]}},
        "socket_timeout": 30,
    }
    if os.path.isfile(COOKIE_FILE):
        ydl_opts["cookiefile"] = COOKIE_FILE

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info_dict = ydl.extract_info(video_url, download=False)
        if not info_dict:
            raise ValueError("yt-dlp 未返回有效信息")

        return ExtractData(
            title=info_dict.get("title") or "Unknown Title",
            description=info_dict.get("description") or "",
            uploader=info_dict.get("uploader") or "unknown",
        )


# ---------- REST 接口 ----------


@app.post("/api/v1/scraper/extract", response_model=ApiResponse)
def extract_video(request: ExtractRequest) -> ApiResponse:
    """
    从视频链接提取 title / description / uploader。
    抓取失败时返回通用错误，不向调用方返回异常细节。
    """
    url = request.url.strip()
    if not url:
        return ApiResponse(code=400, message="url 不能为空", data=None)

    try:
        approved_host = _validate_video_url(url)
    except UnsupportedVideoUrl:
        return ApiResponse(
            code=400,
            message="仅支持 B 站或 YouTube 的 http(s) 视频链接",
            data=None,
        )

    try:
        data = _extract_video_metadata(url)
        return ApiResponse(code=200, message="success", data=data)
    except yt_dlp.utils.DownloadError:
        logger.warning("yt-dlp extraction failed for approved host %s", approved_host)
        return ApiResponse(
            code=500,
            message="视频元数据抓取失败，请确认链接可访问或无需额外验证",
            data=None,
        )
    except Exception as exc:
        logger.error("Unexpected scraper extraction error (%s)", type(exc).__name__)
        return ApiResponse(
            code=500,
            message="视频元数据抓取失败，请稍后重试",
            data=None,
        )

# ---------- 启动入口 ----------


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
