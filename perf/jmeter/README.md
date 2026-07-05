# JMeter Plans

This directory contains three plans:

- `auth-and-query.jmx`: login and management/query interfaces
- `log-upload.jmx`: upload, poll, and report retrieval
- `mixed-workload.jmx`: combined A/B/D business flow with weighted throughput

## Usage

Example:

```powershell
jmeter -n -t perf/jmeter/auth-and-query.jmx `
  -q perf/config/env-dev.properties `
  -Jlogin.username=test `
  -Jlogin.password=test `
  -l perf/data/results/jmeter/auth-query.jtl `
  -e -o perf/data/results/jmeter/auth-query-report
```

## Important Notes

- The plans rely on the standard JMeter HTTP Cookie Manager to reuse the login
  session.
- Management APIs are called through the management base URL.
- Business APIs are called through the business base URL.
- Timestamps in performance endpoints must match `LocalDateTime.parse` format:
  `YYYY-MM-DDTHH:mm:ss`.
