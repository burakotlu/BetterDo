import argparse
import logging
import os
import threading
from .config import Config, load_env
from .database import VideoDatabase
from .http_api import create_server
from .media import VideoMedia
from .providers import create_provider
from .service import VideoService


def main():
    parser = argparse.ArgumentParser(description="BetterDo lesson video API and durable worker")
    parser.add_argument("--env-file")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8080)
    args = parser.parse_args()
    if args.env_file:
        load_env(args.env_file)
    config = Config.from_env()
    config.data_dir.mkdir(parents=True, exist_ok=True)
    # OS releases this lock even after a crash. Prevent two workers for one database.
    lock = (config.data_dir / "worker.lock").open("a+b")
    lock.write(b"0")
    lock.flush()
    lock.seek(0)
    if os.name == "nt":
        import msvcrt
        msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
    else:
        import fcntl
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    database = VideoDatabase(config.data_dir / "videos.db")
    media = VideoMedia(config.data_dir, config.provider)
    service = VideoService(config, database, create_provider(config.provider, config.api_key), media)
    http = create_server((args.host, args.port), service, config.token)
    stop = threading.Event()

    def work():
        while not stop.wait(1):
            try:
                service.tick()
            except Exception as error:
                logging.error("Video worker error (%s)", type(error).__name__)

    worker = threading.Thread(target=work, daemon=True)
    worker.start()
    logging.warning("Video service listening on %s:%s; provider=%s", args.host, args.port, config.provider)
    try:
        http.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        http.server_close()
        worker.join(timeout=240)
        lock.close()


if __name__ == "__main__":
    main()
