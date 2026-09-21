import time
from .base import VideoGenerationProvider, VideoResult


class MockVideoGenerationProvider(VideoGenerationProvider):
    """No network, credentials, speech generation, or charges. Restart-safe job IDs."""
    def __init__(self, clock=time.time):
        self.clock = clock

    def generate_talking_video(self, request, idempotency_key):
        return "mock:{}:{}".format(int(self.clock()), idempotency_key)

    def check_status(self, job_id):
        age = self.clock() - int(job_id.split(":")[1])
        if age < 2:
            return VideoResult("pending")
        if age < 5:
            return VideoResult("processing")
        return VideoResult("completed", "mock://sample", 20.0)
