# ThreshCore Performance Toolkit

This directory contains reusable assets for capacity testing the current
ThreshCore deployment. The toolkit is organized around four workload groups:

- `A`: log upload and analysis task lifecycle
- `B`: AI and SSE streaming interfaces
- `C`: WebSocket real-time performance feed
- `D`: management and query interfaces

## Layout

- `config/env-dev.properties`: editable environment and credential values
- `jmeter/`: JMeter plans for HTTP, multipart upload, and mixed workload
- `k6/`: k6 scripts for SSE and WebSocket scenarios
- `data/`: prompts, query params, generated log samples, and result outputs
- `reports/`: report skeletons and checklists
- `report-template.md`: consolidated execution and conclusion template

## Recommended Execution Order

1. Update `config/env-dev.properties`.
2. Generate or refresh log samples if needed.
3. Run the smoke checklist in `reports/environment-checklist.md`.
4. Execute JMeter baselines for single interfaces.
5. Execute k6 scripts for SSE and WebSocket capacity.
6. Run the mixed workload plan.
7. Fill the report template with measured results.

Quick path (Node scripts, backend dev on 8088+8089):

```bash
node perf/run-all.mjs
```

## Environment Notes

- Business APIs are expected on `8088` under `/award-log`.
- Management APIs are expected on `8089` under `/award-log` when enabled.
- Login uses session cookies. JMeter plans first authenticate through
  `/admin/user/login` on the management surface and then reuse the cookie jar.
- WebSocket `/ws/performance` requires the authenticated session cookie and a
  browser-like `Origin` that matches the configured CORS allowlist.
- SSE results should be analyzed in two ways:
  - system-side timing until first chunk is received
  - end-to-end timing until stream completion, including external AI latency

## Result Storage

Store raw outputs under `data/results/`:

- JMeter `.jtl` files
- JMeter HTML dashboards
- k6 stdout/json summaries
- manual screenshots from Prometheus, host metrics, and DB monitoring

Do not overwrite prior runs; create timestamped subdirectories per batch.
