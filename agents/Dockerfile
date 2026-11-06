FROM python:3.11-slim

WORKDIR /app

COPY pyproject.toml ./
COPY seed_agents/ ./seed_agents/
COPY guardian/ ./guardian/
COPY coach/ ./coach/
COPY underwriting/ ./underwriting/
COPY desk-analyst/ ./desk-analyst/
COPY trust/ ./trust/
COPY shared/ ./shared/

RUN pip install --no-cache-dir .

ENV PYTHONUNBUFFERED=1
ENV AGENT_RUNTIME_PORT=8090

EXPOSE 8090

CMD ["python", "-m", "seed_agents.server"]
