#!/usr/bin/env python3
"""Simple REST receiver for CTP StabilityWebhookPlugin testing.

Features:
- Supports GET, POST, and PUT
- Logs each request to console and JSONL file
- Optional one-time forced failure response for retry testing
"""

import argparse
import datetime as dt
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


class NotifyReceiverHandler(BaseHTTPRequestHandler):
    server_version = "StableNotifyReceiver/1.0"

    def do_GET(self):
        self._handle_request()

    def do_POST(self):
        self._handle_request()

    def do_PUT(self):
        self._handle_request()

    def log_message(self, fmt, *args):
        # Keep output focused on structured request logs from _handle_request.
        return

    def _handle_request(self):
        now = dt.datetime.now(dt.UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
        parsed = urlparse(self.path)
        body_text = ""
        content_length = int(self.headers.get("Content-Length", "0") or "0")
        if content_length > 0:
            body_bytes = self.rfile.read(content_length)
            body_text = body_bytes.decode("utf-8", errors="replace")

        body_json = None
        body_form = None
        content_type = self.headers.get("Content-Type", "")
        if body_text:
            if "application/json" in content_type:
                try:
                    body_json = json.loads(body_text)
                except json.JSONDecodeError:
                    body_json = {"_parse_error": "invalid json"}
            if "application/x-www-form-urlencoded" in content_type:
                body_form = parse_qs(body_text, keep_blank_values=True)

        request_record = {
            "timestampUtc": now,
            "method": self.command,
            "path": parsed.path,
            "query": parse_qs(parsed.query, keep_blank_values=True),
            "headers": {k: v for (k, v) in self.headers.items()},
            "contentType": content_type,
            "body": body_text,
            "bodyJson": body_json,
            "bodyForm": body_form,
            "client": self.client_address[0],
        }

        status_code = 200
        if self.server.fail_once and not self.server.failed_already:
            status_code = self.server.fail_status
            self.server.failed_already = True

        with self.server.log_lock:
            self.server.request_count += 1
            request_record["requestNumber"] = self.server.request_count

            summary = (
                f"[{request_record['requestNumber']}] {request_record['timestampUtc']} "
                f"{request_record['method']} {request_record['path']} "
                f"from {request_record['client']} -> planned {status_code}"
            )
            print(summary, flush=True)

            if request_record["query"]:
                print(f"  query={json.dumps(request_record['query'], ensure_ascii=True)}", flush=True)
            if body_json is not None:
                print(f"  json={json.dumps(body_json, ensure_ascii=True)}", flush=True)
            elif body_form is not None:
                print(f"  form={json.dumps(body_form, ensure_ascii=True)}", flush=True)
            elif body_text:
                print(f"  body={body_text}", flush=True)

            with open(self.server.log_file, "a", encoding="utf-8") as fp:
                fp.write(json.dumps(request_record, ensure_ascii=True) + "\n")

        response = {
            "ok": status_code < 400,
            "status": status_code,
            "requestNumber": request_record["requestNumber"],
        }
        response_bytes = json.dumps(response, ensure_ascii=True).encode("utf-8")

        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(response_bytes)))
        self.end_headers()
        self.wfile.write(response_bytes)


class NotifyReceiverServer(ThreadingHTTPServer):
    def __init__(self, server_address, handler_cls, log_file, fail_once, fail_status):
        super().__init__(server_address, handler_cls)
        self.log_file = log_file
        self.fail_once = fail_once
        self.fail_status = fail_status
        self.failed_already = False
        self.request_count = 0
        self.log_lock = threading.Lock()


def parse_args():
    parser = argparse.ArgumentParser(
        description="Local REST receiver for CTP StabilityWebhookPlugin validation"
    )
    parser.add_argument("--host", default="127.0.0.1", help="Bind host (default: 127.0.0.1)")
    parser.add_argument("--port", type=int, default=18080, help="Bind port (default: 18080)")
    parser.add_argument(
        "--log-file",
        default="stable_notify_requests.jsonl",
        help="JSONL output file (default: stable_notify_requests.jsonl)",
    )
    parser.add_argument(
        "--fail-once",
        action="store_true",
        help="Return an error status for the first request only",
    )
    parser.add_argument(
        "--fail-status",
        type=int,
        default=500,
        help="Status code used with --fail-once (default: 500)",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    server = NotifyReceiverServer(
        (args.host, args.port),
        NotifyReceiverHandler,
        log_file=args.log_file,
        fail_once=args.fail_once,
        fail_status=args.fail_status,
    )

    print(
        f"Stable notify receiver listening on http://{args.host}:{args.port} "
        f"(log file: {args.log_file})",
        flush=True,
    )
    if args.fail_once:
        print(
            f"First request will return {args.fail_status}; subsequent requests return 200.",
            flush=True,
        )

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        print("Receiver stopped.", flush=True)


if __name__ == "__main__":
    main()
