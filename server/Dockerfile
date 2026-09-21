FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY server /app/server
COPY scripts /app/scripts
COPY content /app/content
RUN useradd --create-home betterdo && mkdir /data && chown betterdo:betterdo /data
USER betterdo
ENV VIDEO_DATA_DIR=/data
EXPOSE 8080
CMD ["python", "-m", "server", "--host", "0.0.0.0"]
