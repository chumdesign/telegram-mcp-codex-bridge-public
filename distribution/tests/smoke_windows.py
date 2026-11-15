"""Exercise packaged Windows Java and both STDIO clients using an offline temporary account."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import queue
import secrets
import socket
import subprocess
import sys
import tempfile
import threading

sys.dont_write_bytecode = True


def load_bridge(package, environment):
    previous = os.environ.get("TELEGRAM_BRIDGE_HOME")
    os.environ["TELEGRAM_BRIDGE_HOME"] = environment["TELEGRAM_BRIDGE_HOME"]
    try:
        spec = importlib.util.spec_from_file_location("offline_bridge", package / "bridge.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        if previous is None:
            os.environ.pop("TELEGRAM_BRIDGE_HOME", None)
        else:
            os.environ["TELEGRAM_BRIDGE_HOME"] = previous


def handshake(package, environment):
    child = subprocess.Popen([sys.executable, str(package / "bridge.py"), "mcp"], env=environment,
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             text=True, encoding="utf-8")
    received = queue.Queue()

    def reader():
        for line in child.stdout:
            try:
                received.put(json.loads(line))
            except ValueError:
                received.put({"unexpected_stdout": line[:100]})
        received.put({"transport_eof": True})

    threading.Thread(target=reader, daemon=True).start()

    def send(message):
        child.stdin.write(json.dumps(message) + "\n")
        child.stdin.flush()

    def response(request_id):
        item = received.get(timeout=240)
        if item.get("id") != request_id or "error" in item:
            detail = child.stderr.read()[-4000:] if item.get("transport_eof") else ""
            raise RuntimeError("Offline MCP handshake rejected: " + json.dumps(item) + "\n" + detail)
        return item["result"]

    try:
        send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "offline-bundle-smoke", "version": "1"}}})
        initialize = response(1)
        send({"jsonrpc": "2.0", "method": "notifications/initialized"})
        send({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
        tools = {tool["name"]: tool for tool in response(2)["tools"]}
        for name in ("send_article", "edit_article", "get_article", "inspect_channel_activity", "delete_message"):
            if name not in tools:
                raise RuntimeError("Missing packaged tool " + name)
        if not tools["delete_message"]["annotations"]["destructiveHint"]:
            raise RuntimeError("Destructive tool did not advertise approval semantics")
        for parameter in ("rich_markdown", "rich_html", "blocks", "photo_path", "idempotency_key"):
            if parameter not in tools["send_article"]["inputSchema"]["properties"]:
                raise RuntimeError("Missing Article parameter " + parameter)
        if "photo_path" in tools["send_article"]["inputSchema"].get("required", []):
            raise RuntimeError("Article cover unexpectedly became mandatory")
        output = tools["get_article"]["outputSchema"]
        if "articleBlock" not in output.get("$defs", {}):
            raise RuntimeError("Recursive Article output schema is unavailable")
        if "isCompact" not in output["$defs"]["articleBlock"]["properties"]:
            raise RuntimeError("TDLib table compact property is missing")
        child.stdin.close()
        if child.wait(timeout=12):
            raise RuntimeError("STDIO client exited unsuccessfully")
        return {"serverVersion": initialize["serverInfo"]["version"], "toolCount": len(tools)}
    finally:
        if child.poll() is None:
            child.kill()
            child.wait()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != "win32":
        raise RuntimeError("This smoke test targets the Windows runtime")
    with tempfile.TemporaryDirectory(prefix="telegram bridge offline ") as temporary:
        root = Path(temporary)
        environment = os.environ.copy()
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        environment["PYTHONUTF8"] = "1"
        environment["TELEGRAM_BRIDGE_HOME"] = str(root / "home")
        module = load_bridge(args.package, environment)
        module.secure_directory(module.PRIVATE)
        downloads = root / "downloads"
        downloads.mkdir()
        with socket.socket() as port_socket:
            port_socket.bind(("127.0.0.1", 0))
            port = port_socket.getsockname()[1]
        module.write_json(module.HOME / "settings.json", {"api_id": 0, "port": port,
                          "downloads": str(downloads), "roots": [str(downloads)]})
        module.atomic_write(module.PRIVATE / "transport.key", secrets.token_hex(32).encode())
        version = subprocess.check_output(module.runtime_command() + ["version"], text=True).strip()
        try:
            first = handshake(args.package, environment)
            pid = module.daemon_state()["pid"]
            second = handshake(args.package, environment)
            if module.daemon_state()["pid"] != pid:
                raise RuntimeError("Second Codex client started a different daemon")
            module.stop()
            if module.port_open(port):
                raise RuntimeError("Offline daemon listener remained active after stop")
            report = {"offlineWindowsHandshake": True, "sharedDaemonAcrossClients": True,
                      "nativeApprovalsDoNotBlockInitialize": True, "stopReleasesListener": True,
                      "runtimeVersion": version, "catalog": first, "secondCatalog": second,
                      "articleInputAndRecursiveOutputSchemas": True,
                      "realTelegramAccountUsed": False, "macLiveAcceptance": "pending"}
            args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
            print(json.dumps(report))
        except Exception:
            log = module.PRIVATE / "daemon.log"
            if log.exists():
                print(log.read_text(encoding="utf-8", errors="replace")[-6000:], file=sys.stderr)
            raise
        finally:
            module.stop()


if __name__ == "__main__":
    main()
