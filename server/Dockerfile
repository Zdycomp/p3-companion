# SupRevolt scan server. Standard library only: nothing is pip-installed.
# Multi-arch (Oracle Ampere arm64 and amd64):
#   docker buildx build --platform linux/arm64,linux/amd64 -t suprevolt .
# or on the machine itself:  docker build -t suprevolt .
FROM python:3.11-slim

RUN useradd -r -u 10001 -s /usr/sbin/nologin sr && mkdir /data && chown sr /data && chmod 700 /data
COPY suprevolt /app/suprevolt
WORKDIR /app
USER sr

ENV SUPREVOLT_DATA=/data \
    SUPREVOLT_HOST=0.0.0.0 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
EXPOSE 8787
VOLUME ["/data"]

HEALTHCHECK --interval=60s --timeout=5s --start-period=10s \
  CMD python -c "import os,urllib.request;urllib.request.urlopen('http://127.0.0.1:%s/v1/health' % os.environ.get('PORT','8787'),timeout=4)"

# The first start prints the access token once in the container log (docker logs suprevolt).
# Port: $PORT when the host sets one (Render), else 8787.
CMD ["python", "-m", "suprevolt"]
