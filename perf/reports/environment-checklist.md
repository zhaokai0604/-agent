# Environment Smoke Checklist

Fill this sheet before the first performance run.

## Runtime Facts

- Test date:
- Operator:
- Git commit:
- JDK version:
- Spring profile:
- App host:
- DB host/version:
- Qdrant host/version:
- AI provider/model:
- Kafka enabled:
- Redis enabled:
- Elasticsearch enabled:

## Service Readiness

- [ ] `GET /award-log/actuator/health` returns `UP`
- [ ] `GET /award-log/actuator/prometheus` is reachable from the metrics scraper
- [ ] `POST /award-log/admin/user/login` returns `code=200`
- [ ] `POST /award-log/log/upload` succeeds with a 1 MB sample
- [ ] `GET /award-log/log/task/{taskId}` returns a valid task status
- [ ] `GET /award-log/log/report/{taskId}` succeeds after completion
- [ ] `POST /award-log/api/assistant/chat/stream` returns SSE chunks
- [ ] `GET /award-log/log/diagnose/stream/{taskId}` streams diagnosis chunks
- [ ] WebSocket `/award-log/ws/performance` authenticates and receives pushes
- [ ] `GET /award-log/api/v1/knowledge/search` returns results
- [ ] `GET /award-log/api/alarm/history/list` returns paginated data

## Observability

- [ ] JVM CPU/heap/thread metrics visible
- [ ] Host CPU/memory/disk/network collection started
- [ ] DB slow log or performance view enabled
- [ ] k6 and JMeter output directories created
- [ ] Service log capture window agreed for this run

## Notes

- Environment limitations:
- Temporary workarounds:
- Known bottlenecks before test:
