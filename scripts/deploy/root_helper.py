#!/usr/bin/env python3
"""Root Helper 守护进程：Unix Socket 接收受限 root 命令并审计。"""
import json
import os
import socket
import subprocess
import sys
from pathlib import Path

POLICY_FILE = os.environ.get("ROOT_HELPER_POLICY", "/etc/award-agent/root-helper-policy.json")
SOCKET_PATH = os.environ.get("ROOT_HELPER_SOCKET", "/var/run/award-root-helper.sock")

DEFAULT_POLICY = {
    "allowed_patterns": [
        "systemctl restart nginx",
        "systemctl reload nginx",
        "resolvectl flush-caches",
    ]
}


def load_policy():
    path = Path(POLICY_FILE)
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    return DEFAULT_POLICY


def allowed(command: str, policy: dict) -> bool:
    patterns = policy.get("allowed_patterns", [])
    normalized = " ".join(command.strip().split())
    return any(normalized.startswith(p) or normalized == p for p in patterns)


def handle_client(conn):
    data = conn.recv(4096).decode("utf-8", errors="replace").strip()
    if not data:
        conn.sendall(b"ERROR: empty command\n")
        return
    policy = load_policy()
    if not allowed(data, policy):
        conn.sendall(f"DENIED: {data}\n".encode("utf-8"))
        return
    try:
        result = subprocess.run(
            data, shell=True, capture_output=True, text=True, timeout=60
        )
        out = result.stdout or ""
        err = result.stderr or ""
        payload = out + ("\n" + err if err else "")
        conn.sendall(payload.encode("utf-8"))
    except Exception as e:
        conn.sendall(f"ERROR: {e}\n".encode("utf-8"))


def main():
    if os.path.exists(SOCKET_PATH):
        os.unlink(SOCKET_PATH)
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(SOCKET_PATH)
    os.chmod(SOCKET_PATH, 0o660)
    server.listen(5)
    while True:
        conn, _ = server.accept()
        handle_client(conn)
        conn.close()


if __name__ == "__main__":
    main()
