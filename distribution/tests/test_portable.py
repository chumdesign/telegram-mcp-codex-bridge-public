import importlib.util
import contextlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

COMMON = Path(__file__).resolve().parents[1] / "common"
sys.path.insert(0, str(COMMON))
import bridge
from mcp_stdio import Transport


class PortableTests(unittest.TestCase):
    def test_macos_below_native_library_minimum_stops_before_credentials(self):
        with patch.object(bridge.sys, "platform", "darwin"), \
                patch.object(bridge.platform, "machine", return_value="arm64"), \
                patch.object(bridge.platform, "mac_ver", return_value=("13.6", (), "")), \
                patch.object(bridge, "save_hash") as save, \
                patch.object(bridge, "secure_directory") as secure:
            with self.assertRaisesRegex(RuntimeError, "macOS 14"):
                bridge.setup(bridge.argparse.Namespace())
            save.assert_not_called()
            secure.assert_not_called()

    def test_cold_start_can_exceed_the_old_75_second_budget(self):
        elapsed = [0.0]
        config = {"port": 17433}
        child = MagicMock()
        child.poll.return_value = None
        def advance(seconds):
            elapsed[0] += seconds
        with tempfile.TemporaryDirectory() as folder, \
                patch.object(bridge, "PRIVATE", Path(folder)), \
                patch.object(bridge, "settings", return_value=config), \
                patch.object(bridge, "file_lock", side_effect=lambda *a, **kw: contextlib.nullcontext()) as locking, \
                patch.object(bridge, "daemon_alive", side_effect=lambda: elapsed[0] >= 95), \
                patch.object(bridge, "port_open", side_effect=lambda port: elapsed[0] >= 95), \
                patch.object(bridge.subprocess, "Popen", return_value=child), \
                patch.object(bridge.time, "monotonic", side_effect=lambda: elapsed[0]), \
                patch.object(bridge.time, "sleep", side_effect=advance):
            self.assertEqual(bridge.ensure(), config)
            self.assertGreaterEqual(elapsed[0], 95)
            locking.assert_called_once_with("startup.lock", wait=bridge.STARTUP_TIMEOUT_SECONDS)

    def test_auth_wizard_does_not_start_a_second_configured_tdlib_account(self):
        config = {"api_id": 12345, "port": 17433, "downloads": "downloads", "roots": ["documents"]}
        child = MagicMock(stdout=[])
        child.wait.return_value = 1
        child.poll.return_value = 1
        with patch.object(bridge, "settings", return_value=config), \
                patch.object(bridge, "file_lock", side_effect=lambda *a, **kw: contextlib.nullcontext()), \
                patch.object(bridge, "runtime_command", return_value=["fake-java"]), \
                patch.object(bridge, "load_hash", side_effect=AssertionError("wizard must use interactive credentials")), \
                patch.object(bridge.subprocess, "Popen", return_value=child) as start:
            with self.assertRaises(RuntimeError):
                bridge.auth()
        environment = start.call_args.kwargs["env"]
        self.assertEqual(environment["TDLIB_API_ID"], "0")
        self.assertEqual(environment["TDLIB_API_HASH"], "")

    def test_config_preserves_other_servers_and_rejects_name_collision(self):
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder) / "config.toml"
            previous = '[mcp_servers.other]\ncommand = "other-server"\n'
            destination.write_text(previous)
            script = Path(folder) / "directory with spaces/bridge.py"
            bridge.register_codex(script, destination)
            result = destination.read_text()
            self.assertTrue(result.startswith(previous))
            parsed = bridge.tomllib.loads(result)
            self.assertFalse(parsed["mcp_servers"]["telegram-reader"]["required"])
            self.assertEqual(parsed["mcp_servers"]["telegram-reader"]["args"], ["-X", "utf8", str(script), "mcp"])
            bridge.register_codex(script, destination)
            self.assertEqual(destination.read_text(), result)
            with self.assertRaises(RuntimeError):
                bridge.register_codex(Path(folder) / "another.py", destination)
            self.assertEqual(destination.read_text(), result)

    def test_local_mcp_never_replays_write_after_session_refresh(self):
        from urllib.error import HTTPError
        transport = Transport("not-a-real-key", 17433)
        transport.session = "expired"
        transport.read_tools = {"get_history"}
        with patch.object(transport, "post_once", side_effect=HTTPError("local", 404, "gone", {}, None)) as post:
            with patch.object(transport, "reconnect") as reconnect:
                with self.assertRaises(RuntimeError):
                    transport.post({"id": 1, "method": "tools/call", "params": {"name": "send_file"}})
                reconnect.assert_called_once_with("expired")
                self.assertEqual(post.call_count, 1)
        self.assertTrue(transport.safe_to_replay({"method": "tools/call", "params": {"name": "get_history"}}))
        self.assertFalse(transport.safe_to_replay({"method": "tools/call", "params": {"name": "delete_message"}}))

    def test_shared_lock_excludes_second_owner_and_releases_on_error(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(bridge, "PRIVATE", Path(folder)):
            with self.assertRaises(ValueError):
                with bridge.file_lock("daemon.lock"):
                    with self.assertRaises(RuntimeError):
                        with bridge.file_lock("daemon.lock"):
                            self.fail("second owner acquired the daemon lock")
                    raise ValueError("simulated failed startup")
            with bridge.file_lock("daemon.lock"):
                pass

    @unittest.skipUnless(sys.platform == "win32", "Windows DPAPI roundtrip")
    def test_windows_secret_roundtrip_without_plaintext_file(self):
        clear = b"example-data-not-a-real-credential"
        sealed = bridge.windows_secret(clear, decrypt=False)
        self.assertNotIn(clear, sealed)
        self.assertEqual(bridge.windows_secret(sealed, decrypt=True), clear)


if __name__ == "__main__":
    unittest.main()
