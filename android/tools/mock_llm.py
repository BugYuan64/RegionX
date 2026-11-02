"""RegionX 本地联调用的假 LLM 服务（OpenAI 兼容，SSE 流式）。

只用于本机验证客户端流式解析与界面显示，不属于产品代码。
用法：python tools/mock_llm.py，监听 8765。
"""

import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ANSWER = "牛顿第二定律\n\n物体的加速度与所受合力成正比、与质量成反比。\n即 F = ma。"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.endswith("/models"):
            self._send_json({"data": [{"id": "mock-vision-1"}, {"id": "mock-text-1"}]})
        else:
            self._send_json({"error": "not found"}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        body = json.loads(raw or b"{}")
        has_image = "image_url" in raw.decode("utf-8", "ignore")
        text = ANSWER if has_image else "（未收到图片）\n\n请检查附件。"

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()

        for i in range(0, len(text), 3):
            chunk = {
                "choices": [{"delta": {"content": text[i:i + 3]}, "finish_reason": None}],
                "model": body.get("model", "mock"),
            }
            self.wfile.write(f"data: {json.dumps(chunk, ensure_ascii=False)}\n\n".encode())
            self.wfile.flush()
            time.sleep(0.06)

        self.wfile.write(b'data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n')
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()


if __name__ == "__main__":
    print("mock LLM on http://127.0.0.1:8765/v1")
    ThreadingHTTPServer(("0.0.0.0", 8765), Handler).serve_forever()
