"""Safe recipient checks. Reads self metadata; optional explicit test send to Saved Messages."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

import bridge
from mcp_stdio import Transport


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--send-self", action="store_true", help="Отправить тестовый текст в своё Избранное")
    args = parser.parse_args()
    config = bridge.ensure()
    client = Transport((bridge.PRIVATE / "transport.key").read_text(), config["port"])
    replies = []
    client.emit = replies.append
    client.reconnect(None)

    def rpc(name, arguments):
        request_id = len(replies) + 1
        client.post({"jsonrpc": "2.0", "id": request_id, "method": "tools/call", "params": {"name": name, "arguments": arguments}})
        response = next(item for item in reversed(replies) if item.get("id") == request_id)
        if "error" in response:
            raise RuntimeError(response["error"]["message"])
        result = response["result"]
        if result.get("isError"):
            raise RuntimeError(result["content"][0]["text"])
        return result.get("structuredContent", {}).get("data") or json.loads(result["content"][0]["text"])

    manifest = rpc("_manifest", {})
    names = {item["name"] for item in manifest["tools"]}
    required = {"send_article", "edit_article", "get_article", "download_media", "reply_to_message", "inspect_channel_activity"}
    if not required.issubset(names):
        raise RuntimeError("В каталоге отсутствуют инструменты форка: " + ", ".join(sorted(required - names)))
    me = rpc("get_me", {})
    report = {"connected": True, "platform": sys.platform, "tools": manifest["toolCount"],
              "ownAccountResolved": bool(me), "selfMessageSent": False, "nativeDialogVisuallyVerified": False}
    if args.send_self:
        sent = rpc("send_message", {"chat_id": "self", "text": "Проверка Codex Telegram Bridge. Сообщение отправлено в моё Избранное."})
        report["selfMessageSent"] = True
        report["messageId"] = sent.get("messageId")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
