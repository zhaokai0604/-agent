#!/usr/bin/env python3
"""轻量 Prompt Injection 边车（规则兜底，可替换为 ONNX 模型）。"""
from http.server import BaseHTTPRequestHandler, HTTPServer
import json


MALICIOUS_HINTS = [
    "ignore previous instructions",
    "忽略上文",
    "system prompt",
    "越狱",
    "bypass security",
]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        try:
            payload = json.loads(body)
            text = str(payload.get("text", "")).lower()
        except json.JSONDecodeError:
            text = body.lower()
        malicious = any(h in text for h in MALICIOUS_HINTS)
        out = json.dumps({"malicious": malicious})
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(out.encode("utf-8"))

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 8099), Handler).serve_forever()
