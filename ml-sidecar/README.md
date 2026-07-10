# ML Sidecar — LSTM Direction Predictor

Python FastAPI sidecar for LSTM-based intraday direction prediction. Runs alongside the kite-trading Java app.

## Build

```bash
docker build -t sharadprsn/ml-sidecar:latest .
```

## Push

```bash
docker push sharadprsn/ml-sidecar:latest
```

## Run (standalone)

```bash
docker run -d \
  --name ml-sidecar \
  -p 8000:8000 \
  -e JAVA_APP_URL=http://kite-trading:443 \
  -v ./data:/app/data \
  sharadprsn/ml-sidecar:latest
```

## Run (with docker-compose)

```bash
docker compose --profile ml up --build
```

## Verify

```bash
curl http://localhost:8000/health
```

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Model status and path |
| POST | `/predict` | Direction prediction from 10 snapshots |
| POST | `/train` | Retrain LSTM from H2 data |
