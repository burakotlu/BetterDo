from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional


@dataclass(frozen=True)
class VideoRequest:
    script: str
    avatar: str
    voice: str
    language: str
    word: str
    aspect_ratio: str = "9:16"
    subtitles: bool = True


@dataclass(frozen=True)
class VideoResult:
    status: str
    video_url: Optional[str] = None
    duration: Optional[float] = None


class ProviderError(Exception):
    """Only safe messages cross the provider boundary."""
    def __init__(self, message, transient=False, retry_after=30):
        super().__init__(message)
        self.transient = transient
        self.retry_after = max(5, min(300, retry_after))


class VideoGenerationProvider(ABC):
    # Implementations must honor the same key on retried submissions.
    @abstractmethod
    def generate_talking_video(self, request: VideoRequest, idempotency_key: str) -> str:
        pass

    @abstractmethod
    def check_status(self, job_id: str) -> VideoResult:
        pass
