"""Portable local Codex/Telegram daemon manager. Python 3.11+, standard library only."""
from __future__ import annotations

import argparse
import contextlib
import ctypes
import datetime as dt
import getpass
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import tomllib
import urllib.request
import webbrowser

APP = Path(__file__).resolve().parent
DEFAULT_HOME = (Path(os.environ.get("LOCALAPPDATA", Path.home())) / "TelegramMcpCodexBridge"
                if sys.platform == "win32" else Path.home() / "Library/Application Support/TelegramMcpCodexBridge")
HOME = Path(os.environ.get("TELEGRAM_BRIDGE_HOME", DEFAULT_HOME)).expanduser().absolute()
PRIVATE = HOME / "private"
PREFIXES = ("TDLIB_", "TELEGRAM_", "MCP_", "SPRING_", "SERVER_", "AUTH_WIZARD_", "PUBLIC_SEARCH_", "ANTI_SPAM_")


def secure_directory(path: Path) -> None:
    if path.is_symlink():
        raise RuntimeError("Каталог установки или данных не должен быть символической ссылкой")
    path.mkdir(parents=True, exist_ok=True)
    if sys.platform != "win32":
        path.chmod(0o700)


def atomic_write(path: Path, data: bytes) -> None:
    secure_directory(path.parent)
    temporary = path.with_name(path.name + "." + secrets.token_hex(6) + ".tmp")
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def write_json(path: Path, value: object) -> None:
    atomic_write(path, json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8"))


@contextlib.contextmanager
def file_lock(name: str, wait: float = 0):
    secure_directory(PRIVATE)
    path = PRIVATE / name
    with path.open("a+b") as handle:
        if path.stat().st_size == 0:
            handle.write(b"0")
            handle.flush()
        deadline = time.monotonic() + wait
        while True:
            try:
                if sys.platform == "win32":
                    import msvcrt
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise RuntimeError("Блокировка Telegram занята. Завершите текущий запуск или вход") from None
                time.sleep(0.2)
        try:
            yield
        finally:
            if sys.platform == "win32":
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle, fcntl.LOCK_UN)


def settings() -> dict:
    path = HOME / "settings.json"
    if not path.is_file():
        raise RuntimeError("Сначала выполните setup")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value.get("port"), int) or not 1024 <= value["port"] <= 65535:
        raise RuntimeError("Некорректный порт в settings.json")
    return value


def vault_service() -> str:
    suffix = hashlib.sha256(str(HOME).encode()).hexdigest()[:16]
    return "telegram-mcp-codex-bridge-" + suffix


def windows_secret(value: bytes, decrypt: bool) -> bytes:
    from ctypes import wintypes

    class Blob(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]

    buffer = (ctypes.c_ubyte * len(value)).from_buffer_copy(value)
    source = Blob(len(value), buffer)
    target = Blob()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    function = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                         ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    function.restype = wintypes.BOOL
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise RuntimeError("Windows DPAPI не смог обработать локальный секрет")
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        kernel.LocalFree(target.data)


def load_hash() -> str:
    if sys.platform == "win32":
        return windows_secret((PRIVATE / "api-hash.dpapi").read_bytes(), decrypt=True).decode()
    result = subprocess.run(["/usr/bin/security", "find-generic-password", "-s", vault_service(),
                             "-a", "telegram-api", "-w"], capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError("API hash недоступен в macOS Keychain. Повторите setup")
    return result.stdout.decode().strip()


def save_hash(value: str) -> None:
    if not re.fullmatch(r"[0-9a-fA-F]{32}", value):
        raise RuntimeError("API hash должен содержать 32 шестнадцатеричных символа")
    if sys.platform == "win32":
        atomic_write(PRIVATE / "api-hash.dpapi", windows_secret(value.encode(), decrypt=False))
    else:
        # Interactive security input keeps the secret out of process arguments and shell history.
        command = f'add-generic-password -U -s "{vault_service()}" -a "telegram-api" -w "{value}"\n'
        subprocess.run(["/usr/bin/security", "-i"], input=command.encode(), capture_output=True,
                       timeout=60, check=True)
        if load_hash() != value:
            raise RuntimeError("Не удалось проверить сохранение API hash в Keychain")


def runtime_command() -> list[str]:
    manifest = json.loads((APP / "manifest.json").read_text(encoding="utf-8"))
    jar = APP / "engine/server.jar"
    with jar.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    if digest != manifest["jar_sha256"]:
        raise RuntimeError("Контрольная сумма JAR не совпадает с manifest.json")
    relative_java = ("engine/runtime/bin/java.exe" if sys.platform == "win32"
                     else "engine/runtime/Contents/Home/bin/java")
    java = APP / relative_java
    if not java.is_file():
        raise RuntimeError("Java runtime отсутствует. Распакуйте полный комплект для своей ОС")
    return [str(java), "--enable-preview", "-Djava.awt.headless=true", "-jar", str(jar)]


def server_environment(config: dict) -> dict[str, str]:
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(PREFIXES) and key not in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")}
    # An explicitly unconfigured profile can advertise MCP before Telegram login.
    api_hash = load_hash() if config["api_id"] > 0 else ""
    if config["api_id"] > 0 and not re.fullmatch(r"[0-9a-fA-F]{32}", api_hash):
        raise RuntimeError("Локальный API hash повреждён")
    env.update({
        "TDLIB_API_ID": str(config["api_id"]), "TDLIB_API_HASH": api_hash,
        "TELEGRAM_MCP_DATA_DIR": str(PRIVATE), "TDLIB_DATA_DIR": str(PRIVATE / "tdlib"),
        "TDLIB_DOWNLOADS_DIR": config["downloads"], "TDLIB_AUTH_READY_TIMEOUT": "30s",
        "TDLIB_DEVICE_MODEL": "Codex Telegram Bridge", "TDLIB_LOG_VERBOSITY": "0",
        "TELEGRAM_ALLOWED_CHAT_IDS": "", "TELEGRAM_BLOCKED_CHAT_IDS": "777000",
        "MCP_TRANSPORT": "streamable-http", "MCP_AUTH_MODE": "api-key",
        # This fork registers every MCP handler explicitly in McpConfig.
        "SPRING_AI_MCP_SERVER_ANNOTATION_SCANNER_ENABLED": "false",
        "MCP_API_KEY_FILE": str(PRIVATE / "transport.key"), "MCP_READ_ONLY": "false",
        "MCP_TOOL_PROFILE": "all", "MCP_TOOL_ALLOW": "", "MCP_TOOL_DENY": "register_internal_chat",
        "MCP_WRITE_SCOPE_ENABLED": "true", "MCP_PERSONAL_MODE": "true",
        "MCP_WRITE_SCOPE_ALLOW_SAVED_MESSAGES_TEXT": "true", "MCP_WRITE_SCOPE_ALLOW_MEDIA_DOWNLOAD": "true",
        "MCP_MEDIA_DOWNLOAD_ROOT": config["downloads"], "MCP_FILE_ROOTS": ",".join(config["roots"]),
        "MCP_DENY_SENSITIVE_FILES": "true", "MCP_AUDIT_ENABLED": "true", "MCP_AUDIT_LOG_ARGS": "false",
        "MCP_DESTRUCTIVE_APPROVAL": "native", "MCP_DESTRUCTIVE_APPROVAL_TIMEOUT": "60s",
        "MCP_CONFIRMATION_REQUIRED": "false", "PUBLIC_SEARCH_ENABLED": "true",
        "ANTI_SPAM_NOTIFIER_ENABLED": "false", "SERVER_ADDRESS": "127.0.0.1",
        "SERVER_PORT": str(config["port"]), "AUTH_WIZARD_ENABLED": "true", "AUTH_WIZARD_NONCE": "",
        "LOGGING_LEVEL_ROOT": "WARN", "LOGGING_LEVEL_IT_TDLIGHT": "ERROR", "MCP_LOG_LEVEL": "WARN",
        "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE": "health,info",
        "MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS": "never",
    })
    return env


def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False


def daemon_state() -> dict:
    try:
        return json.loads((PRIVATE / "daemon.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def daemon_alive() -> bool:
    state = daemon_state()
    if not state.get("instance"):
        return False
    try:
        with file_lock("daemon.lock"):
            return False
    except RuntimeError:
        return True


STARTUP_TIMEOUT_SECONDS = 180


def ensure() -> dict:
    config = settings()
    with file_lock("startup.lock", wait=STARTUP_TIMEOUT_SECONDS):
        alive = daemon_alive()
        spawned = None
        if port_open(config["port"]) and not alive:
            raise RuntimeError("Порт занят другим процессом. Выберите свободный порт при setup")
        if not alive:
            command = [sys.executable, "-X", "utf8", str(APP / "bridge.py"), "serve"]
            flags = {"creationflags": subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW} if sys.platform == "win32" else {"start_new_session": True}
            with (PRIVATE / "daemon.log").open("ab") as log:
                spawned = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=log, stderr=log, **flags)
        deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
        while time.monotonic() < deadline:
            if daemon_alive() and port_open(config["port"]):
                return config
            if spawned is not None and spawned.poll() is not None:
                raise RuntimeError("Сервер завершился при запуске. Проверьте private/daemon.log")
            time.sleep(0.4)
        raise RuntimeError("Сервер не запустился. Проверьте doctor и private/daemon.log")


def close_process(process: subprocess.Popen) -> None:
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=25)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def serve() -> None:
    config = settings()
    with file_lock("daemon.lock"):
        if port_open(config["port"]):
            raise RuntimeError("Порт занят. Запуск второго TDLib остановлен")
        instance = secrets.token_hex(16)
        state = {"pid": os.getpid(), "instance": instance, "port": config["port"]}
        child = subprocess.Popen(runtime_command() + ["serve", "--transport", "streamable-http"],
                                 stdin=subprocess.DEVNULL, env=server_environment(config))
        write_json(PRIVATE / "daemon.json", state)
        stop_requested = False
        try:
            while child.poll() is None:
                request = PRIVATE / "stop.request"
                if request.exists() and request.read_text(encoding="ascii") == instance:
                    stop_requested = True
                    child.terminate()
                    break
                time.sleep(0.5)
            try:
                code = child.wait(timeout=25)
            except subprocess.TimeoutExpired:
                child.kill()
                code = child.wait()
            if code and not stop_requested:
                raise RuntimeError(f"Telegram MCP завершился с кодом {code}")
        finally:
            close_process(child)
            if daemon_state().get("instance") == instance:
                (PRIVATE / "daemon.json").unlink(missing_ok=True)
                (PRIVATE / "stop.request").unlink(missing_ok=True)


def stop() -> None:
    if not daemon_alive():
        return
    atomic_write(PRIVATE / "stop.request", daemon_state()["instance"].encode("ascii"))
    deadline = time.monotonic() + 30
    while daemon_alive() and time.monotonic() < deadline:
        time.sleep(0.3)
    if daemon_alive():
        raise RuntimeError("Сервер ещё завершает работу. Проверьте private/daemon.log")


def auth() -> None:
    config = settings()
    with file_lock("startup.lock", wait=STARTUP_TIMEOUT_SECONDS), file_lock("daemon.lock"):
        # Only the interactive orchestrator may open TDLib during login. A configured
        # static account here would compete with it for the same session directory.
        auth_environment = server_environment({**config, "api_id": 0})
        process = subprocess.Popen(runtime_command() + ["auth", "--method", "qr"], env=auth_environment,
                                   stdout=subprocess.PIPE, stderr=None, text=True, encoding="utf-8", errors="replace")
        try:
            for line in process.stdout:
                found = re.fullmatch(r"Telegram authentication wizard: (http://127\.0\.0\.1:\d+/setup\?nonce=[A-Za-z0-9_-]+)\s*", line)
                if found:
                    webbrowser.open(found[1])
                    print("Открыт локальный мастер Telegram. Введите данные только в его окне.")
                elif "http://127.0.0.1" not in line:
                    print(line, end="")
            if process.wait():
                raise RuntimeError("Вход Telegram не завершён")
        finally:
            close_process(process)
    ensure()
    print("Telegram подключён. Выполните doctor и перезапустите Codex для загрузки инструментов.")


def codex_snippet(script: Path) -> str:
    quote = lambda value: json.dumps(str(value), ensure_ascii=False)
    return ("\n[mcp_servers.telegram-reader]\ncommand = " + quote(sys.executable) +
            '\nargs = ["-X", "utf8", ' + quote(script) + ', "mcp"]\nrequired = false\nstartup_timeout_sec = 240\ntool_timeout_sec = 120\n')


def codex_config_path() -> Path:
    return Path(os.environ.get("CODEX_HOME", Path.home() / ".codex")) / "config.toml"


def registration_text(script: Path, destination: Path) -> str | None:
    previous = destination.read_text(encoding="utf-8") if destination.exists() else ""
    parsed = tomllib.loads(previous)
    existing = parsed.get("mcp_servers", {}).get("telegram-reader")
    if existing:
        if existing.get("command") == sys.executable and existing.get("args") == ["-X", "utf8", str(script), "mcp"]:
            return None
        raise RuntimeError("telegram-reader уже настроен в Codex. Сопоставьте его с generated-codex.toml вручную")
    updated = previous + codex_snippet(script)
    tomllib.loads(updated)
    return updated


def register_codex(script: Path, config_path: Path | None = None) -> None:
    destination = config_path or codex_config_path()
    updated = registration_text(script, destination)
    if updated is None:
        return
    if destination.exists():
        stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%d-%H%M%S")
        shutil.copy2(destination, destination.with_name("config.toml.before-telegram-" + stamp))
    atomic_write(destination, updated.encode("utf-8"))


def setup(arguments: argparse.Namespace) -> None:
    machine = platform.machine().lower()
    if sys.platform == "darwin" and machine != "arm64":
        raise RuntimeError("Этот Mac-комплект требует Apple Silicon и ARM64 Python. Intel Mac не поддержан TDLight-сборкой")
    if sys.platform == "darwin":
        release = platform.mac_ver()[0]
        if not release or not release.split(".")[0].isdigit() or int(release.split(".")[0]) < 14:
            raise RuntimeError("Требуется macOS 14 или новее: нативная TDLib 1.8.68 из MCP 1.20.0 собрана с minimum macOS 14.0")
    if sys.platform not in ("darwin", "win32"):
        raise RuntimeError("Комплект предназначен для macOS или Windows")
    if sys.platform == "win32" and not Path(r"C:\Program Files\PowerShell\7\pwsh.exe").is_file():
        raise RuntimeError("Установите PowerShell 7 для нативных подтверждений")
    target = HOME / "app"
    if target.exists():
        raise RuntimeError("Установка уже существует. Используйте auth, doctor или отдельный TELEGRAM_BRIDGE_HOME")
    if arguments.register_codex:
        registration_text(target / "bridge.py", codex_config_path())
    secure_directory(HOME)
    secure_directory(PRIVATE)
    if sys.platform == "win32":
        user = subprocess.check_output(["whoami"], text=True).strip()
        subprocess.run(["icacls", str(HOME), "/inheritance:r", "/grant:r", user + ":(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"],
                       check=True, stdout=subprocess.DEVNULL)
    api_id = arguments.api_id or int(input("Telegram API ID вашего приложения: ").strip())
    if not 0 < api_id <= 2_147_483_647:
        raise RuntimeError("Некорректный API ID")
    save_hash(getpass.getpass("Telegram API hash (скрытый ввод): ").strip())
    downloads = Path.home() / "Downloads/Telegram MCP Files"
    secure_directory(downloads)
    secure_directory(HOME / "publish-ready")
    roots = [str(Path(value).expanduser().resolve()) for value in (arguments.root or [str(Path.home() / "Documents")])]
    roots += [str(downloads), str(HOME / "publish-ready")]
    if any("," in root for root in roots):
        raise RuntimeError("Путь с запятой не поддержан конфигурацией каталогов MCP")
    shutil.copytree(APP, target, ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "tests"))
    write_json(HOME / "settings.json", {"api_id": api_id, "port": arguments.port, "downloads": str(downloads), "roots": roots})
    atomic_write(PRIVATE / "transport.key", secrets.token_hex(32).encode("ascii"))
    installed_script = target / "bridge.py"
    snippet = codex_snippet(installed_script)
    atomic_write(HOME / "generated-codex.toml", snippet.encode("utf-8"))
    skill = Path(os.environ.get("CODEX_HOME", Path.home() / ".codex")) / "skills/telegram/SKILL.md"
    if skill.exists():
        shutil.copy2(skill, HOME / "telegram-skill-before-install.md")
    atomic_write(skill, (target / "skill/SKILL.md").read_bytes())
    if arguments.register_codex:
        register_codex(installed_script)
    print("Комплект установлен в", HOME)
    print("Следующий шаг:", sys.executable, installed_script, "auth")


def main() -> None:
    parser = argparse.ArgumentParser(description="Codex Telegram Bridge")
    sub = parser.add_subparsers(dest="command", required=True)
    install = sub.add_parser("setup")
    install.add_argument("--api-id", type=int)
    install.add_argument("--root", action="append")
    install.add_argument("--port", type=int, default=17433, choices=range(1024, 65536), metavar="PORT")
    install.add_argument("--register-codex", action="store_true")
    for name in ("auth", "serve", "start", "stop", "doctor", "config", "mcp"):
        sub.add_parser(name)
    args = parser.parse_args()
    if args.command == "setup":
        setup(args)
    elif args.command == "auth":
        auth()
    elif args.command == "serve":
        serve()
    elif args.command == "start":
        config = ensure()
        print(json.dumps({"ready": True, "endpoint": f'http://127.0.0.1:{config["port"]}/mcp'}))
    elif args.command == "stop":
        stop()
    elif args.command == "config":
        print(codex_snippet(APP / "bridge.py"))
    elif args.command == "doctor":
        config = settings()
        runtime_command()
        print(json.dumps({"home": str(HOME), "platform": sys.platform, "architecture": platform.machine(),
                          "port": config["port"], "daemon": daemon_alive(), "listening": port_open(config["port"]),
                          "jar_checksum": "verified"}, ensure_ascii=False, indent=2))
    else:
        config = ensure()
        from mcp_stdio import run
        run((PRIVATE / "transport.key").read_text(encoding="ascii"), config["port"])


if __name__ == "__main__":
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        print("Telegram Bridge:", str(error) if not isinstance(error, KeyboardInterrupt) else "операция прервана", file=sys.stderr)
        sys.exit(1)
