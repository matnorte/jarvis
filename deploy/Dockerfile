FROM python:3.12-slim

COPY --from=ghcr.io/astral-sh/uv:latest /uv /usr/local/bin/uv

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 \
    PATH="/app/.venv/bin:$PATH"

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY server ./server

VOLUME ["/root/.cache/huggingface", "/app/cache"]

# Bind to loopback only: the container uses host networking, so 0.0.0.0 would
# expose the gateway publicly. Caddy proxies to 127.0.0.1:8000.
# Frame cap 256 KiB (audio frames are a few KiB) and at most 64 concurrent
# connections, so unauthenticated clients cannot exhaust memory or sockets.
CMD ["uvicorn", "server.main:app", "--host", "127.0.0.1", "--port", "8000", \
     "--ws-max-size", "262144", "--limit-concurrency", "64"]
