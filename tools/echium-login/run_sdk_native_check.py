from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path


REQUIRED_MARKERS = (
    "hoyo_network_alloc_pat",
    "sdk_fallback_alloc_pat",
    "patched_hoyo_network_alloc",
    "patched_sdk_fallback_alloc",
)


def main() -> int:
    parser = argparse.ArgumentParser(description="运行 SDK 原生 URL 分配点候选的固定输入检查。")
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--bindings", type=Path, required=True)
    parser.add_argument("--client-root", type=Path, required=True)
    parser.add_argument("--odin", type=Path, required=True)
    parser.add_argument("--work", type=Path, required=True)
    args = parser.parse_args()

    source_text = "\n".join(path.read_text(encoding="utf-8") for path in args.source.rglob("*.odin"))
    missing = [marker for marker in REQUIRED_MARKERS if marker not in source_text]
    if missing:
        print(f"SDK_NATIVE_ADAPTER_ABSENT MISSING={','.join(missing)}")
        return 2

    verify = subprocess.run(
        [
            sys.executable,
            "-B",
            "-X",
            "utf8",
            str(Path(__file__).with_name("verify_sdk_bindings.py")),
            str(args.bindings),
            str(args.client_root),
        ],
        check=False,
    )
    if verify.returncode != 0:
        print(f"SDK_NATIVE_BINDINGS_FAILED EXIT={verify.returncode}")
        return verify.returncode

    if args.work.exists():
        shutil.rmtree(args.work)
    args.work.mkdir(parents=True)
    shutil.copytree(args.source, args.work / "echium")
    shutil.copytree(Path(__file__).with_name("sdk-native-check"), args.work / "sdk-native-check")
    sdk_run = subprocess.run(
        [str(args.odin), "run", ".", "-vet", "-strict-style", "-o:speed"],
        cwd=args.work / "sdk-native-check",
        check=False,
    )
    print(f"SDK_NATIVE_CHECK_EXIT={sdk_run.returncode}")
    return sdk_run.returncode


if __name__ == "__main__":
    raise SystemExit(main())
