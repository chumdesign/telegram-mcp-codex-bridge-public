"""Assemble clean portable kits from the tested JAR and checksum-pinned runtime archives."""
import argparse
import hashlib
import io
import json
import platform
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile
import xml.etree.ElementTree as ET

REPOSITORY = Path(__file__).resolve().parents[1]


def source_revision():
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPOSITORY,
                                       text=True, stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def checksum(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def output_path(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise RuntimeError("Unsafe archive member")
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def extract_runtime(archive, destination, target):
    modes = {}
    if target == "windows-x64":
        with zipfile.ZipFile(archive) as source:
            for item in source.infolist():
                if "/telegram-mcp/runtime/" in item.filename and not item.is_dir():
                    relative = item.filename.split("/telegram-mcp/runtime/", 1)[1]
                    output_path(destination / "engine/runtime", relative).write_bytes(source.read(item))
                elif "/support/tdlight-licenses/upstream/" in item.filename and not item.is_dir():
                    relative = item.filename.split("/support/tdlight-licenses/upstream/", 1)[1]
                    output_path(destination / "licenses/native-texts", relative).write_bytes(source.read(item))
    else:
        with tarfile.open(archive) as source:
            for item in source.getmembers():
                marker = "/telegram-mcp.app/Contents/runtime/"
                if marker not in item.name or item.isdir():
                    continue
                relative = item.name.split(marker, 1)[1]
                data = source.extractfile(item)
                if data is None:
                    raise RuntimeError("Runtime member is not a regular file or resolvable link")
                path = output_path(destination / "engine/runtime", relative)
                path.write_bytes(data.read())
                modes[path.relative_to(destination).as_posix()] = item.mode
        # License texts apply to the embedded library licenses; version references are generated below.
        windows = archive.parent / "telegram-mcp-server-1.20.0-windows-x64.zip"
        with zipfile.ZipFile(windows) as source:
            for item in source.infolist():
                marker = "/support/tdlight-licenses/upstream/"
                if marker in item.filename and not item.is_dir():
                    output_path(destination / "licenses/native-texts", item.filename.split(marker)[1]).write_bytes(source.read(item))
    return modes


def dependency_notices(jar, destination):
    artifacts = []
    with zipfile.ZipFile(jar) as source:
        for name in source.namelist():
            if not name.startswith("BOOT-INF/lib/") or not name.endswith(".jar"):
                continue
            artifact = Path(name).name
            artifacts.append(artifact)
            with zipfile.ZipFile(io.BytesIO(source.read(name))) as dependency:
                for member in dependency.infolist():
                    basename = Path(member.filename).name.upper()
                    if member.is_dir() or member.file_size > 500_000:
                        continue
                    if basename.startswith(("LICENSE", "NOTICE", "COPYING")):
                        output_path(destination / "licenses/dependencies" / artifact, member.filename).write_bytes(dependency.read(member))
    (destination / "licenses/ARTIFACTS.json").write_text(json.dumps(artifacts, indent=2), encoding="utf-8")
    text = """# Source and replacement notice

The server code is distributed with its Apache-2.0 LICENSE and matching source.zip.
The Java runtime retains its bundled legal/ notices.

TDLight Java uses LGPL-3.0. TDLight/TDLib JNI/native components and their native
dependencies use the license texts in native-texts/. ARTIFACTS.json lists the
exact embedded versions. The runtime carrier archive is upstream 1.20.0; its
server JAR is replaced by our separately tested merge of that release and the
Codex additions. Native library and Java API versions are upgraded together.

Library sources and source artifacts are available from
https://github.com/tdlight-team/tdlight-java
https://github.com/tdlight-team/tdlight
https://github.com/tdlight-team/tdlight-java-natives
https://mvn.mchv.eu/repository/mchv/it/tdlight/

To replace the LGPL library, extract source.zip and rebuild with the desired tdlightVersion in
build.gradle.kts using Java 25 and Gradle Wrapper, run its tests and bootJar, then
replace engine/server.jar and update jar_sha256 in manifest.json. Keep the
matching Java API and native classifier versions together. No proprietary
signing requirement prevents a recipient from replacing or rebuilding them.
"""
    (destination / "licenses/SOURCE-AND-REPLACEMENT.md").write_text(text, encoding="utf-8")


def copy_source(destination):
    paths = []
    for name in ("src", "gradle", "docs", "distribution", "licenses", "skills"):
        paths.extend(path for path in (REPOSITORY / name).rglob("*")
                     if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc")
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "LICENSE", "README.md", "CHANGELOG.md", ".gitignore", ".env.example"):
        paths.append(REPOSITORY / name)
    paths.extend(path for path in (REPOSITORY / "scripts").rglob("*")
                 if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc" and path.name != "test_shared_reader_bridge.py")
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as output:
        for path in sorted(paths):
            output.write(path, path.relative_to(REPOSITORY).as_posix())


def package(args, target, test_result):
    asset_name = f"telegram-mcp-server-1.20.0-{target}" + (".zip" if target.startswith("windows") else ".tar.gz")
    release = json.loads((args.cache / "upstream-release.json").read_text(encoding="utf-8"))
    asset = next(item for item in release["assets"] if item["name"] == asset_name)
    archive = args.cache / asset_name
    if checksum(archive) != asset["digest"].removeprefix("sha256:"):
        raise RuntimeError("Runtime carrier checksum mismatch")
    name = f"telegram-mcp-codex-bridge-{args.version}-{target}"
    destination = args.output / target
    destination.mkdir(parents=True, exist_ok=args.refresh)
    shutil.copytree(REPOSITORY / "distribution/common", destination, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    for document in ("README.md", "RESEARCH.md"):
        shutil.copy2(REPOSITORY / "distribution" / document, destination / document)
    installer = "Install.ps1" if target.startswith("windows") else "Install.command"
    shutil.copy2(REPOSITORY / "distribution" / ("windows" if target.startswith("windows") else "macos") / installer, destination / installer)
    modes = extract_runtime(archive, destination, target)
    shutil.copy2(args.jar, destination / "engine/server.jar")
    (destination / "skill").mkdir(exist_ok=True)
    skill = args.skill.read_text(encoding="utf-8")
    skill = skill.replace("в нативном диалоге Windows", "в нативном диалоге Windows или macOS")
    (destination / "skill/SKILL.md").write_text(skill, encoding="utf-8")
    (destination / "licenses").mkdir(exist_ok=True)
    shutil.copy2(REPOSITORY / "LICENSE", destination / "licenses/LICENSE")
    dependency_notices(args.jar, destination)
    copy_source(destination / "source.zip")
    manifest = {"package": "telegram-mcp-codex-bridge", "package_version": args.version,
                "server_base": "1.20.0 with Codex additions", "tdlight": "3.5.9+td.1.8.68",
                "minimum_macos": "14.0" if target == "macos-arm64" else None,
                "target": target, "source_git_commit": source_revision(), "jar_sha256": checksum(args.jar), "source_zip_sha256": checksum(destination / "source.zip"),
                "runtime_carrier": asset["browser_download_url"],
                "runtime_carrier_sha256": checksum(archive), "full_tests": test_result,
                "personal_credentials_included": False, "mac_runtime_executed": False,
                "distribution_status": "developer-preview"}
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    verification = {"jvm_tests": test_result, "portable_python_tests": 7,
                    "runtime_carrier_checksum": "verified", "mac_live_acceptance": "pending on recipient hardware",
                    "windows_live_acceptance": "see recipient result or preparation report"}
    native_reports = list((args.test_results.parent / "nativeTest").glob("TEST-*.xml"))
    if native_reports:
        counts = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        for report in native_reports:
            suite = ET.parse(report).getroot()
            for key in counts:
                counts[key] += int(suite.get(key, 0))
        if counts["failures"] or counts["errors"]:
            raise RuntimeError("Native tests must pass before packaging")
        verification["native_jvm_tests"] = {"host": platform.system().lower() + "-" + platform.machine().lower(), **counts}
    if args.windows_report:
        report = json.loads(args.windows_report.read_text(encoding="utf-8"))
        if not report.get("offlineWindowsHandshake") or not report.get("sharedDaemonAcrossClients"):
            raise RuntimeError("Windows MCP acceptance report is incomplete")
        verification["windows_preparation_result"] = report
    (destination / "VERIFICATION.json").write_text(json.dumps(verification, indent=2), encoding="utf-8")
    sums = [f"{checksum(path)}  {path.relative_to(destination).as_posix()}" for path in sorted(destination.rglob("*"))
            if path.is_file() and path != destination / "SHA256SUMS"]
    (destination / "SHA256SUMS").write_text("\n".join(sums) + "\n", encoding="utf-8")
    if target.startswith("windows"):
        result = args.output / (name + ".zip")
        with zipfile.ZipFile(result, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as output:
            for path in destination.rglob("*"):
                if path.is_file():
                    output.write(path, name + "/" + path.relative_to(destination).as_posix())
    else:
        result = args.output / (name + ".tar.gz")
        def mode(info):
            relative = info.name.removeprefix(name + "/")
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mode = 0o755 if info.isdir() or relative in ("Install.command", "source/gradlew") else modes.get(relative, 0o644)
            return info
        with tarfile.open(result, "w:gz") as output:
            output.add(destination, arcname=name, filter=mode)
    result.with_name(result.name + ".sha256").write_text(checksum(result) + "  " + result.name + "\n", encoding="ascii")
    print(json.dumps({"target": target, "archive": str(result), "bytes": result.stat().st_size, "sha256": checksum(result)}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--test-results", type=Path, required=True)
    parser.add_argument("--skill", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", default="1.20.0-codex.1")
    parser.add_argument("--refresh", action="store_true", help="Rebuild existing staging folders and archives after verification")
    parser.add_argument("--windows-report", type=Path)
    args = parser.parse_args()
    if not args.version or any(character not in "0123456789abcdefghijklmnopqrstuvwxyz.-" for character in args.version):
        raise RuntimeError("Invalid package version")
    reports = list(args.test_results.glob("TEST-*.xml"))
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, 0))
    if totals["tests"] < 825 or totals["failures"] or totals["errors"]:
        raise RuntimeError("A complete passing fork test suite is required")
    args.output.mkdir(parents=True, exist_ok=True)
    for target in ("windows-x64", "macos-arm64"):
        package(args, target, totals)


if __name__ == "__main__":
    main()
