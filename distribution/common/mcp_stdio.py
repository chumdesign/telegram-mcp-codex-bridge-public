"""STDIO adapter for one authenticated, loopback-only MCP daemon."""
import concurrent.futures
import json
import sys
import threading
import urllib.error
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class Transport:
    def __init__(self, key, port):
        self.key = key
        self.endpoint = f"http://127.0.0.1:{port}/mcp"
        self.protocol = "2025-06-18"
        self.session = None
        self.output_lock = threading.Lock()
        self.session_lock = threading.Lock()
        self.read_tools = set()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def emit(self, message):
        with self.output_lock:
            sys.stdout.write(json.dumps(message, ensure_ascii=False, separators=(",", ":")) + "\n")
            sys.stdout.flush()

    def request(self, message, session):
        headers = {"Authorization": "Bearer " + self.key, "Content-Type": "application/json",
                   "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": self.protocol}
        if session:
            headers["Mcp-Session-Id"] = session
        request = urllib.request.Request(self.endpoint, data=json.dumps(message).encode(), headers=headers)
        return self.opener.open(request, timeout=110)

    def forward(self, message, request):
        result = message.get("result", {})
        if request.get("method") == "initialize" and result:
            self.protocol = result.get("protocolVersion", self.protocol)
        if request.get("method") == "tools/list":
            self.read_tools = {tool["name"] for tool in result.get("tools", [])
                               if tool.get("annotations", {}).get("readOnlyHint") is True}
        self.emit(message)

    @staticmethod
    def messages(response):
        if response.headers.get_content_type() != "text/event-stream":
            content = response.read()
            if content.strip():
                yield json.loads(content)
            return
        data = []
        for raw in response:
            line = raw.decode("utf-8").rstrip("\r\n")
            if line.startswith("data:"):
                data.append(line[5:].lstrip())
            elif not line and data:
                yield json.loads("\n".join(data))
                data = []
        if data:
            yield json.loads("\n".join(data))

    def post_once(self, message, session):
        with self.request(message, session) as response:
            if message.get("method") == "initialize":
                self.session = response.headers.get("Mcp-Session-Id")
                if not self.session:
                    raise RuntimeError("MCP did not return a session")
            for result in self.messages(response):
                self.forward(result, message)

    def reconnect(self, stale):
        with self.session_lock:
            if self.session != stale:
                return
            initialize = {"jsonrpc": "2.0", "id": "bridge-reconnect", "method": "initialize", "params": {
                "protocolVersion": self.protocol, "capabilities": {},
                "clientInfo": {"name": "telegram-mcp-codex-bridge", "version": "0.1"}}}
            with self.request(initialize, None) as response:
                session = response.headers.get("Mcp-Session-Id")
                replies = list(self.messages(response))
                if not session or not replies or "result" not in replies[-1]:
                    raise RuntimeError("Could not initialize a replacement MCP session")
                self.protocol = replies[-1]["result"].get("protocolVersion", self.protocol)
            with self.request({"jsonrpc": "2.0", "method": "notifications/initialized"}, session) as response:
                response.read()
            self.session = session

    def safe_to_replay(self, message):
        method = message.get("method")
        return method in ("tools/list", "resources/list", "prompts/list", "ping") or (
            method == "tools/call" and message.get("params", {}).get("name") in self.read_tools)

    def post(self, message):
        session = None if message.get("method") == "initialize" else self.session
        try:
            self.post_once(message, session)
        except urllib.error.HTTPError as error:
            if error.code != 404 or not session:
                raise
            self.reconnect(session)
            if not self.safe_to_replay(message):
                raise RuntimeError("MCP session restored. Inspect the outcome before repeating this action") from None
            self.post_once(message, self.session)

    def handle(self, message):
        try:
            self.post(message)
        except Exception as error:
            if "id" in message:
                self.emit({"jsonrpc": "2.0", "id": message["id"], "error": {
                    "code": -32000, "message": str(error)}})


def run(key, port):
    bridge = Transport(key, port)
    pending = threading.Semaphore(16)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
        for raw in sys.stdin:
            if len(raw) > 4 * 1024 * 1024:
                raise RuntimeError("MCP request exceeds the transport input bound")
            message = json.loads(raw)
            if message.get("method") in ("initialize", "notifications/initialized"):
                bridge.handle(message)
            else:
                pending.acquire()
                future = workers.submit(bridge.handle, message)
                future.add_done_callback(lambda done: pending.release())
