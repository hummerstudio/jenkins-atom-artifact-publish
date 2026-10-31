#!/usr/bin/env python3
"""An artifact repository that records what was uploaded.

    fake_repo.py <port> <log> [--status 200]
"""
import http.server
import json
import sys

port, log_path = int(sys.argv[1]), sys.argv[2]
status = 200
args = sys.argv[3:]
while args:
    if args.pop(0) == "--status":
        status = int(args.pop(0))


class Handler(http.server.BaseHTTPRequestHandler):
    def do_PUT(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else b""
        with open(log_path, "a", encoding="utf-8") as log:
            log.write(json.dumps({
                "path": self.path,
                "authorization": self.headers.get("Authorization"),
                "checksum": self.headers.get("X-Checksum-Sha256"),
                "contentType": self.headers.get("Content-Type"),
                "size": len(body),
            }) + "\n")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def log_message(self, *args):
        pass


http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
