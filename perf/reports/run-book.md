# Execution Run Book

## Batch Naming

Use timestamped folders, for example:

- `perf/data/results/jmeter/20260625-baseline`
- `perf/data/results/k6/20260625-ai`

## Phase Order

1. Smoke verification
2. Interface baseline
3. Log-upload capacity
4. AI/SSE capacity
5. WebSocket capacity
6. Mixed workload
7. Stability run
8. Enhanced middleware comparison

## Per-Batch Checklist

- [ ] Confirm the app was restarted after any config change
- [ ] Confirm DB/Qdrant/AI dependencies are healthy
- [ ] Confirm test files and prompts are unchanged from the previous batch
- [ ] Record exact concurrency and duration
- [ ] Record start and end timestamps
- [ ] Save raw JMeter `.jtl` and HTML output
- [ ] Save raw k6 output
- [ ] Save CPU, memory, GC, thread, DB, and network screenshots
- [ ] Link the batch to a report section

## Stop Conditions

Stop increasing load when any of the following occurs:

- error rate exceeds `1%` for light/query interfaces
- upload completion success falls below `100%`
- SSE streams show sustained disconnects or timeouts
- WebSocket push cadence exceeds the agreed jitter threshold
- JVM heap or thread count shows sustained runaway growth
- DB saturation causes cascading failures
