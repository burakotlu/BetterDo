"""Archive captioned videos and burn a persistent vocabulary heading into the MP4."""
import ipaddress
import shutil
import socket
import subprocess
import textwrap
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, build_opener, HTTPRedirectHandler


def check_public_url(url):
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("Invalid video download URL")
    addresses = socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM)
    if not addresses or any(not ipaddress.ip_address(item[4][0]).is_global for item in addresses):
        raise ValueError("Video downloads require a public host")


class PublicRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        check_public_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url, destination):
    check_public_url(url)
    # No provider credentials are sent to video/CDN hosts.
    with build_opener(PublicRedirect()).open(Request(url), timeout=30) as response, destination.open("wb") as out:
        total = 0
        while True:
            chunk = response.read(65536)
            if not chunk:
                break
            total += len(chunk)
            if total > 100_000_000:
                raise ValueError("Video exceeds 100 MB")
            out.write(chunk)


class VideoMedia:
    def __init__(self, directory, provider):
        self.directory = Path(directory).resolve() / "media"
        self.directory.mkdir(parents=True, exist_ok=True)
        self.provider = provider
        if provider != "mock" and not shutil.which("ffmpeg"):
            raise ValueError("Install FFmpeg with drawtext support before enabling real videos")

    def finish(self, job_id, result, request, pronunciation):
        output = self.directory / (job_id + ".mp4")
        if output.exists():
            return "/media/" + output.name
        temporary = self.directory / (job_id + ".tmp.mp4")
        if self.provider == "mock":
            shutil.copyfile(Path(__file__).with_name("assets") / "mock-video.mp4", temporary)
        else:
            work = self.directory / job_id
            work.mkdir(exist_ok=True)
            source = work / "source.mp4"
            try:
                download(result.video_url, source)
                heading = "\n".join(textwrap.wrap(request.word, width=24))
                (work / "heading.txt").write_text(heading, encoding="utf-8")
                (work / "pronunciation.txt").write_text(pronunciation, encoding="utf-8")
                filters = ("scale=720:1280:force_original_aspect_ratio=decrease,"
                           "pad=720:1280:(ow-iw)/2:(oh-ih)/2:color=0x183E34,setsar=1,"
                           "drawbox=x=0:y=0:w=iw:h=220:color=0x183E34:t=fill,"
                           "drawtext=textfile=heading.txt:expansion=none:fontcolor=white:fontsize=46:x=(w-tw)/2:y=35,"
                           "drawtext=textfile=pronunciation.txt:expansion=none:fontcolor=white:fontsize=26:x=(w-tw)/2:y=175")
                subprocess.run(["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", "source.mp4",
                                "-vf", filters, "-c:v", "libx264", "-preset", "fast", "-crf", "23",
                                "-pix_fmt", "yuv420p", "-c:a", "aac", "-movflags", "+faststart", str(temporary)],
                               cwd=str(work), check=True, timeout=180, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            finally:
                # Only remove known scratch files created by this job.
                for name in ("source.mp4", "heading.txt", "pronunciation.txt"):
                    path = work / name
                    if path.exists():
                        path.unlink()
                work.rmdir()
        temporary.replace(output)
        return "/media/" + output.name
