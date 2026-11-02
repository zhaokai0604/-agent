"""Two-agent Cerebrum/AIOS smoke flow using synthetic evidence only."""
from __future__ import annotations

import concurrent.futures
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from cerebrum.config.config_manager import config
from cerebrum.llm.apis import llm_chat_with_tool_call_output


def emit(event: str, **fields: Any) -> None:
    print(json.dumps({"time": datetime.now(timezone.utc).isoformat(), "event": event, **fields}, ensure_ascii=False))


def fake_capacity_probe() -> dict[str, Any]:
    """A deterministic fixture; never touches host files or services."""
    return {
        "fixture_id": "disk-log-incident-001",
        "filesystem": {"mount": "/srv", "used_percent": 93, "sampled_at": "2026-11-01T10:00:00Z"},
        "largest_growth": {"path": "/srv/demo/app.log", "growth_mb_10m": 640},
        "service": {"name": "demo-api", "health": "degraded", "error_window": "09:58-10:02Z"},
        "note": "Synthetic benchmark fixture; this is not a real host observation.",
    }


def ask_aios(agent_name: str, user_text: str) -> tuple[str, int]:
    started = time.perf_counter()
    response = llm_chat_with_tool_call_output(
        agent_name=agent_name,
        messages=[{"role": "user", "content": user_text}],
        base_url=config.get_kernel_url(),
        tools=[],
    )
    elapsed_ms = round((time.perf_counter() - started) * 1000)
    payload = response.get("response", {}) if isinstance(response, dict) else {}
    content = payload.get("content") if isinstance(payload, dict) else None
    if not content:
        # Keep the real response for diagnosis, while bounding output size.
        content = json.dumps(response, ensure_ascii=False, default=str)[:4000]
    return str(content)[:4000], elapsed_ms


def main() -> int:
    fixture = fake_capacity_probe()
    emit("FAKE_TOOL_RESULT", tool="fake_capacity_probe", result=fixture)
    emit("RUN_START", agents=["disk_agent", "log_agent"], fixture=fixture["fixture_id"])

    disk_task = (
        "你是磁盘与主机观测智能体。仅根据以下合成观测输出 JSON，包含 role、summary、evidence_ids、question_for_log_agent。"
        "不要提出真实写操作。合成观测：" + json.dumps(fixture, ensure_ascii=False)
    )
    log_task = (
        "你是日志诊断智能体。仅根据以下合成观测输出 JSON，包含 role、summary、evidence_ids、question_for_disk_agent。"
        "不要提出真实写操作。合成观测：" + json.dumps(fixture, ensure_ascii=False)
    )

    try:
        config.get_kernel_url()  # Fail early if Cerebrum has no usable Kernel configuration.
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            disk_future = pool.submit(ask_aios, "disk_agent", disk_task)
            log_future = pool.submit(ask_aios, "log_agent", log_task)
            disk_first, log_first = disk_future.result(), log_future.result()

        disk_reply, disk_ms = disk_first
        log_reply, log_ms = log_first
        emit("AGENT_REPLY", agent="disk_agent", phase="parallel-analysis", duration_ms=disk_ms, summary=disk_reply)
        emit("AGENT_REPLY", agent="log_agent", phase="parallel-analysis", duration_ms=log_ms, summary=log_reply)
        emit("DISK_TO_LOG", from_agent="disk_agent", to_agent="log_agent", summary=disk_reply[:1200])
        log_to_disk_task = (
            "收到磁盘智能体的结构化摘要，请核对日志增长是否能解释磁盘观测，并给出证据边界。"
            "只输出 JSON。原始合成观测：" + json.dumps(fixture, ensure_ascii=False) + "；对方摘要：" + disk_reply
        )
        log_second, log_second_ms = ask_aios("log_agent", log_to_disk_task)
        emit("AGENT_REPLY", agent="log_agent", phase="reply-to-disk", duration_ms=log_second_ms, summary=log_second)
        emit("LOG_TO_DISK", from_agent="log_agent", to_agent="disk_agent", summary=log_second[:1200])
        disk_to_log_task = (
            "日志智能体已回复，请复核其结论是否与原始容量证据一致；给出最终交接摘要，不得声称真实主机已修复。"
            "只输出 JSON。原始合成观测：" + json.dumps(fixture, ensure_ascii=False)
            + "；你的首轮摘要：" + disk_reply + "；日志回复：" + log_second
        )
        disk_second, disk_second_ms = ask_aios("disk_agent", disk_to_log_task)
        emit("AGENT_REPLY", agent="disk_agent", phase="final-check", duration_ms=disk_second_ms, summary=disk_second)
        emit("RUN_COMPLETE", model_calls=4, parallel_initial_calls=2, bidirectional_pairs=1,
             total_model_ms=disk_ms + log_ms + log_second_ms + disk_second_ms,
             evidence_scope="synthetic fixture only")
        return 0
    except Exception as exc:  # Preserve the actual SDK/kernel failure for feasibility diagnosis.
        emit("RUN_FAILED", error_type=type(exc).__name__, error=str(exc)[:1000])
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
