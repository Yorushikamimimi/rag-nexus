"""
Yorushika 视频元数据抓取微服务。
被动响应 RESTful API，仅负责 extract，不向 Spring Boot 推送。
"""
import logging
import os
import traceback
from typing import Any

import yt_dlp
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

# 项目根目录的 cookies.txt（Netscape 格式），B 站 cookie 仅对 bilibili.com 链接有效
COOKIE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cookies.txt")

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Yorushika Scraper Service", version="1.0.0")


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


# ---------- 核心抓取逻辑（迁移自 yorushika_agent） ----------


def _extract_video_metadata(video_url: str) -> ExtractData:
    """
    使用 yt-dlp 提取视频元数据和简介。
    cookies.txt 为 B 站 Cookie 时仅对 bilibili.com 生效；YouTube 需单独导出 cookie。
    """
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
            uploader=info_dict.get("uploader") or "n-buna",
        )


# ---------- REST 接口 ----------


@app.post("/api/v1/scraper/extract", response_model=ApiResponse)
def extract_video(request: ExtractRequest) -> ApiResponse:
    """
    从视频链接提取 title / description / uploader。
    抓取失败时返回 code=500 及错误信息，绝不 Crash。
    """
    url = request.url.strip()
    if not url:
        return ApiResponse(code=400, message="url 不能为空", data=None)

    try:
        data = _extract_video_metadata(url)
        return ApiResponse(code=200, message="success", data=data)
    except yt_dlp.utils.DownloadError as e:
        err_msg = f"yt-dlp 抓取失败（可能需登录/验证）: {e}"
        logger.warning(err_msg)
        return ApiResponse(code=500, message=err_msg, data=None)
    except Exception as e:
        err_msg = f"{type(e).__name__}: {e}"
        stack = traceback.format_exc()
        logger.error("抓取异常:\n%s", stack)
        return ApiResponse(code=500, message=f"{err_msg}\n{stack}", data=None)


# ---------- 启动入口 ----------


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
