import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

import scraper_service


class ScraperUrlValidationTests(unittest.TestCase):
    def test_supported_platform_hosts_are_accepted(self):
        urls = [
            "https://www.bilibili.com/video/BV1xx411c7mD",
            "http://m.bilibili.com/video/BV1xx411c7mD",
            "https://b23.tv/short-code",
            "https://youtube.com/watch?v=example",
            "https://www.youtube.com/watch?v=example",
            "https://m.youtube.com/watch?v=example",
            "https://youtu.be/example",
            "HTTPS://WWW.YOUTUBE.COM/watch?v=example",
        ]
        expected = scraper_service.ExtractData(
            title="Example", description="Description", uploader="Uploader"
        )

        for url in urls:
            with self.subTest(url=url), patch.object(
                scraper_service, "_extract_video_metadata", return_value=expected
            ) as extract:
                response = scraper_service.extract_video(
                    scraper_service.ExtractRequest(url=url)
                )
                self.assertEqual(response.code, 200)
                extract.assert_called_once_with(url)

    def test_unsupported_or_ambiguous_urls_are_rejected_before_extraction(self):
        urls = [
            "file:///etc/passwd",
            "https://youtube.com.evil.example/watch?v=x",
            "https://evil-youtube.com/watch?v=x",
            "https://user:pass@youtube.com/watch?v=x",
            "https://youtube.com@127.0.0.1/watch?v=x",
            "https://youtube.com:443/watch?v=x",
            "https://youtube.com:/watch?v=x",
            "https://youtube.com./watch?v=x",
            "https://127.0.0.1/video",
            "http://10.0.0.1/latest/meta-data",
            "https://[::1]/video",
            "https://www.y\u043eutube.com/watch?v=x",
            "https://www.youtube.com/\n@127.0.0.1/video",
            "https://[::1",
        ]

        for url in urls:
            with self.subTest(url=url), patch.object(
                scraper_service, "_extract_video_metadata"
            ) as extract:
                response = scraper_service.extract_video(
                    scraper_service.ExtractRequest(url=url)
                )
                self.assertEqual(response.code, 400)
                self.assertIsNone(response.data)
                extract.assert_not_called()

    def test_direct_metadata_calls_validate_before_yt_dlp(self):
        with patch.object(scraper_service.yt_dlp, "YoutubeDL") as youtube_dl:
            with self.assertRaises(scraper_service.UnsupportedVideoUrl):
                scraper_service._extract_video_metadata("https://attacker.example/video")
            youtube_dl.assert_not_called()

    def test_download_error_response_does_not_expose_exception_text(self):
        private_marker = "/private/runtime/path/cookies.txt"
        download_error = scraper_service.yt_dlp.utils.DownloadError(private_marker)

        with patch.object(
            scraper_service,
            "_extract_video_metadata",
            side_effect=download_error,
        ):
            response = scraper_service.extract_video(
                scraper_service.ExtractRequest(
                    url="https://www.youtube.com/watch?v=example"
                )
            )

        self.assertEqual(response.code, 500)
        self.assertNotIn(private_marker, response.message)
        self.assertNotIn("Traceback", response.message)

    def test_unexpected_error_response_does_not_expose_traceback_or_path(self):
        private_marker = "/private/runtime/path/cookies.txt"
        error = RuntimeError(f"failed to read {private_marker}")

        with patch.object(scraper_service, "_extract_video_metadata", side_effect=error):
            response = scraper_service.extract_video(
                scraper_service.ExtractRequest(
                    url="https://www.youtube.com/watch?v=example"
                )
            )

        self.assertEqual(response.code, 500)
        self.assertNotIn(private_marker, response.message)
        self.assertNotIn("Traceback", response.message)


if __name__ == "__main__":
    unittest.main()
