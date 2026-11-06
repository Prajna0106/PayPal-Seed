FROM python:3.11-slim

WORKDIR /app

COPY pyproject.toml ./
RUN pip install --no-cache-dir httpx pydantic fastapi uvicorn pyyaml

COPY seed_agents/ ./seed_agents/
COPY guardian/ ./guardian/
COPY coach/ ./coach/
COPY underwriting/ ./underwriting/
COPY desk-analyst/ ./desk-analyst/
COPY shared/ ./shared/

ENV PYTHONUNBUFFERED=1
ENV PYTHONPATH=/app
ENV AGENT_RUNTIME_PORT=8090

EXPOSE 8090

CMD ["python", "-m", "seed_agents.server"]
