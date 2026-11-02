import json, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def do_GET(self):
        body = json.dumps({"data":[{"id":"deepseek-chat"},{"id":"gpt-4o"},{"id":"claude-sonnet-4-5"},{"id":"qwen-max"}]}).encode()
        self.send_response(200); self.send_header("Content-Type","application/json")
        self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        n=int(self.headers.get("Content-Length","0")); self.rfile.read(n)
        text = "最大值为 f(2) = 5\n\n解释：配方得 f(x) = -(x-2)² + 5，对称轴 x=2 在区间内。"
        self.send_response(200); self.send_header("Content-Type","text/event-stream")
        self.send_header("Connection","close"); self.end_headers()
        for i in range(0,len(text),6):
            d={"choices":[{"index":0,"delta":{"content":text[i:i+6]},"finish_reason":None}]}
            self.wfile.write(f"data: {json.dumps(d,ensure_ascii=False)}\n\n".encode()); self.wfile.flush(); time.sleep(0.05)
        self.wfile.write(b"data: [DONE]\n\n"); self.wfile.flush()
ThreadingHTTPServer(("127.0.0.1",18765),H).serve_forever()
