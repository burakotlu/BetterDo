import os
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load_env(path):
    """Small dotenv reader: literal KEY=value, no shell expansion or execution."""
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


@dataclass(frozen=True)
class Config:
    provider: str = "mock"
    api_key: str = ""
    avatar: str = "mock-teacher"
    voice: str = "mock-voice"
    token: str = ""
    data_dir: Path = ROOT / "server-data"
    catalog: Path = ROOT / "content/lessons.json"
    daily_limit: int = 10

    @classmethod
    def from_env(cls):
        provider = os.getenv("AI_VIDEO_PROVIDER", "mock")
        config = cls(provider=provider, api_key=os.getenv("AI_VIDEO_API_KEY", ""),
                     avatar=os.getenv("AI_TEACHER_AVATAR_ID") or "mock-teacher",
                     voice=os.getenv("AI_TEACHER_VOICE_ID") or "mock-voice",
                     token=os.getenv("VIDEO_API_TOKEN", ""),
                     data_dir=Path(os.getenv("VIDEO_DATA_DIR", str(ROOT / "server-data"))).resolve(),
                     catalog=Path(os.getenv("VIDEO_CATALOG_PATH", str(ROOT / "content/lessons.json"))),
                     daily_limit=int(os.getenv("VIDEO_DAILY_LIMIT", "10")))
        if len(config.token) < 32 or not config.token.isascii() or any(c.isspace() for c in config.token):
            raise ValueError("VIDEO_API_TOKEN must be at least 32 ASCII characters without whitespace")
        if not 1 <= config.daily_limit <= 1000:
            raise ValueError("VIDEO_DAILY_LIMIT must be between 1 and 1000")
        if provider not in ("mock", "heygen"):
            raise ValueError("AI_VIDEO_PROVIDER must be mock or heygen")
        if provider == "heygen" and (not config.api_key or config.avatar == "mock-teacher" or config.voice == "mock-voice"):
            raise ValueError("HeyGen requires AI_VIDEO_API_KEY, AI_TEACHER_AVATAR_ID and AI_TEACHER_VOICE_ID")
        return config
