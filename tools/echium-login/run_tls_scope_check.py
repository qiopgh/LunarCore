"""在全新工作目录运行已交付的纯合成原生用例，不加载或扫描游戏文件。"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--odin", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()
    source = args.source.resolve(strict=True)
    odin = args.odin.resolve(strict=True)
    work = args.work.resolve()
    if work.exists():
        parser.error("工作目录必须不存在；不会删除或覆盖既有目录")
    if not (source / "patches" / "tls_mitm.odin").is_file():
        print("TLS_SCOPE_SOURCE_ABSENT")
        return 2
    fixture = Path(__file__).with_name("tls-scope-check") / "main.odin"
    work.mkdir(parents=True)
    shutil.copy2(fixture, work / "main.odin")
    command = [str(odin), "run", ".", "-vet", "-strict-style", "-collection:candidate=" + str(source)]
    result = subprocess.run(command, cwd=work, capture_output=True, check=False)
    (work / "stdout.txt").write_bytes(result.stdout)
    (work / "stderr.txt").write_bytes(result.stderr)
    record = {"event": "TLS_SCOPE_NATIVE_COMMAND", "command": command, "cwd": str(work),
              "exit_status": result.returncode, "stdout": result.stdout.decode("utf-8", errors="strict"),
              "stderr": result.stderr.decode("utf-8", errors="strict"),
              "test_sha256": hashlib.sha256(fixture.read_bytes()).hexdigest(),
              "source_sha256": hashlib.sha256((source / "patches" / "tls_mitm.odin").read_bytes()).hexdigest(),
              "client_started": False, "client_files_read": False}
    (work / "result.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(record["stdout"], end="")
    if record["stderr"]:
        print(record["stderr"], end="")
    print("TLS_SCOPE_NATIVE_EXIT=" + str(result.returncode))
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
