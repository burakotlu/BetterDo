"""Provider implementations are registered here, never in the Android app."""
from .base import VideoGenerationProvider, VideoRequest, VideoResult, ProviderError
from .mock import MockVideoGenerationProvider
from .heygen import HeyGenVideoGenerationProvider


def create_provider(name, api_key):
    if name == "mock":
        return MockVideoGenerationProvider()
    if name == "heygen":
        return HeyGenVideoGenerationProvider(api_key)
    raise ValueError("Unsupported AI_VIDEO_PROVIDER")
