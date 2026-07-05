# Test Data Notes

## Log Samples

The `logs/` directory contains generated text files for upload pressure tests.
Naming convention:

- `log-small-*-1mb.log`
- `log-medium-*-10mb.log`
- `log-large-*-50mb.log`

Each size has three variants:

- `normal`: steady informational traffic
- `anomaly`: dense errors and latency spikes
- `dirty`: mixed formats, partial lines, and noise

## Query Parameters

`query-params.csv` contains reusable values for:

- performance trend/statistics queries
- alarm history queries
- knowledge search queries
- patrol dashboard queries

Adjust timestamps and task IDs to match the environment before formal runs.

## Prompts

`prompts.csv` is used by the k6 SSE script. Keep the prompt set stable across
comparison batches so AI-side variance is easier to explain.
