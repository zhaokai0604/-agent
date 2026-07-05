# ThreshCore Performance Report

## 1. Test Overview

- Test date:
- Operator:
- Objective:
- Environment class:
- Git commit:
- Application profile:
- AI provider/model:
- Included middleware:

## 2. Environment Details

| Item | Value |
| --- | --- |
| App host | |
| App CPU / memory | |
| DB host / version | |
| Qdrant version | |
| Kafka | |
| Redis | |
| Elasticsearch | |
| Prometheus scrape path | `/award-log/actuator/prometheus` |

## 3. Data Set

- Log samples:
- Historical task count:
- Alarm history count:
- Performance sample count:
- Patrol history count:
- Knowledge document count:
- AI prompt set version:

## 4. Phase Results

### 4.1 Smoke

- Login:
- Upload:
- Task polling:
- Report retrieval:
- SSE:
- WebSocket:
- Knowledge search:

### 4.2 Interface Baseline

| Interface | Concurrency | p50 | p95 | p99 | Error Rate | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |

### 4.3 Log Upload Capacity

| File Size | Concurrency | Completion Rate | Task Complete Time p95 | Poll p95 | Notes |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

### 4.4 AI and SSE

| Scenario | Concurrency | First Chunk p95 | Full Stream p95 | Timeout Rate | AI Error Notes |
| --- | --- | --- | --- | --- | --- |
| | | | | | |

### 4.5 WebSocket

| Connections | Connect Success | Push Jitter | Disconnect Rate | Notes |
| --- | --- | --- | --- | --- |
| | | | | |

### 4.6 Mixed Workload

| Total Users | Duration | Pass/Fail | CPU Peak | Heap Peak | DB Notes | Capacity Conclusion |
| --- | --- | --- | --- | --- | --- | --- |
| | | | | | | |

### 4.7 Stability

- Stable concurrency:
- Run duration:
- Heap trend:
- GC summary:
- Thread trend:
- DB connection trend:
- AI rate limit summary:

## 5. Bottleneck Analysis

- First bottleneck observed:
- Evidence:
- Whether bottleneck is internal or external:
- Follow-up checks:

## 6. Recommended Production Limits

- Recommended concurrent users:
- Recommended simultaneous WebSocket connections:
- Recommended simultaneous AI streams:
- Recommended max upload size:
- Recommended deployment shape:
- Middleware recommendations:

## 7. Risks And Next Actions

- Outstanding risk 1:
- Outstanding risk 2:
- Next optimization item 1:
- Next optimization item 2:
