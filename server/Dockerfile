# Build from the repository root so the shared MKBook validator is included:
#   docker build -f server/Dockerfile -t mkread-library .
FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY server/requirements.txt .
# Servers in mainland China can pass e.g. --build-arg PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple/
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir --index-url "$PIP_INDEX_URL" -r requirements.txt
COPY tools/mkbook/mkbook.py ./mkbook.py
COPY server/app ./app
RUN useradd --system --uid 10001 mkread && mkdir -p /data && chown mkread /data
USER mkread
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/healthz')"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
