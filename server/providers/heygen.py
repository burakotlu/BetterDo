"""HeyGen v3 adapter. No other module knows HeyGen endpoints or payloads.

Contract: https://developers.heygen.com/reference/create-video
          https://developers.heygen.com/reference/get-video
"""
import json
import re
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, HTTPRedirectHandler
from .base import VideoGenerationProvider, VideoResult, ProviderError


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class HeyGenVideoGenerationProvider(VideoGenerationProvider):
    def __init__(self, api_key):
        self.api_key = api_key

    def _request(self, path, payload=None, key=None):
        headers = {"X-Api-Key": self.api_key, "Accept": "application/json"}
        if key:
            headers["Idempotency-Key"] = key
        data = None
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        try:
            request = Request("https://api.heygen.com/v3/videos" + path, data=data, headers=headers)
            with build_opener(NoRedirect()).open(request, timeout=20) as response:
                body = response.read(1_000_001)
                if len(body) > 1_000_000:
                    raise ValueError("Oversized response")
                return json.loads(body)["data"]
        except HTTPError as error:
            retry = error.headers.get("Retry-After", "30")
            raise ProviderError("Video provider request failed (HTTP {}).".format(error.code),
                                error.code in (409, 429) or error.code >= 500,
                                int(retry) if retry.isdigit() else 30) from None
        except (URLError, OSError, ValueError, KeyError, TypeError):
            raise ProviderError("Video provider could not be reached or returned an invalid response.", True) from None

    def generate_talking_video(self, request, idempotency_key):
        payload = {"type": "avatar", "avatar_id": request.avatar, "voice_id": request.voice,
                   "script": request.script, "aspect_ratio": request.aspect_ratio,
                   "resolution": "720p", "output_format": "mp4", "title": request.word}
        if request.subtitles:
            payload["caption"] = {"file_format": "srt", "style": "default"}
        result = self._request("", payload, idempotency_key)
        job_id = result.get("video_id")
        if not isinstance(job_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", job_id):
            raise ProviderError("Video provider returned no job ID.", True)
        return job_id

    def check_status(self, job_id):
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", job_id):
            raise ProviderError("Invalid provider job ID.")
        result = self._request("/" + job_id)
        state = result.get("status")
        if state == "completed":
            # Require the captioned version; do not silently publish an uncaptioned clip.
            url = result.get("captioned_video_url")
            if not isinstance(url, str) or not url.startswith("https://"):
                raise ProviderError("Captioned video is not ready.", True)
            duration = result.get("duration")
            return VideoResult("completed", url, float(duration) if duration else None)
        if state == "failed":
            return VideoResult("failed")
        if state in ("waiting", "pending", "processing"):
            return VideoResult("pending" if state in ("waiting", "pending") else "processing")
        raise ProviderError("Unknown video provider status.", True)
