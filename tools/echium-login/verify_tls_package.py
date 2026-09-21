"""验证可移植 TLS 交付包及可选重构源码；不读取客户端、连接网络或加载 DLL。"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    package = args.package.resolve()
    manifest = json.loads((package / "sources.json").read_text(encoding="utf-8-sig"))
    required = ("run_tls_scope_check.py", "tls-scope-check/main.odin", "Echium.local-tls.example.json", "managed-domains.txt", "local-login-observation.json")
    missing = [name for name in required if not (package / name).is_file()]
    if "tls_delivery" not in manifest or missing:
        print(json.dumps({"event": "TLS_PACKAGE_ABSENT", "missing": missing}, ensure_ascii=True))
        return 2
    delivery = manifest["tls_delivery"]
    template = json.loads((package / "Echium.local-tls.example.json").read_text(encoding="utf-8"))
    domains = (package / "managed-domains.txt").read_text(encoding="ascii").splitlines()
    observation = json.loads((package / "local-login-observation.json").read_text(encoding="utf-8"))
    checks = {
        "patch_hash": digest(package / "echium-login.patch") == manifest["patch_sha256"],
        "tls_source_recorded": "patches/tls_mitm.odin" in manifest["modified_source_sha256"],
        "native_test_hash": digest(package / "tls-scope-check/main.odin") == delivery["native_test_sha256"],
        "domain_list_hash": digest(package / "managed-domains.txt") == delivery["domain_list_sha256"],
        "exact_60_unique_domains": len(domains) == len(set(domains)) == 60,
        "template_domains_match": template.get("tls_mitm_hosts") == domains,
        "default_disabled": template.get("local_tls_mitm") is False,
        "runtime_observation_hash": digest(package / "local-login-observation.json") == delivery["runtime_observation_sha256"],
        "runtime_boundary_explicit": observation.get("independent_runtime_script_required_in_observed_run") is True,
        "no_full_login_overclaim": observation.get("complete_login_claimed") is False and manifest["observed"].get("real_local_login_verified") is False,
        "no_compiled_artifacts": not any(path.suffix.lower() in {".dll", ".exe", ".lib", ".bmp", ".pcap", ".sqlite"} for path in package.rglob("*") if path.is_file()),
    }
    if args.source:
        for name, expected in manifest["modified_source_sha256"].items():
            path = args.source / name
            checks["source:" + name] = path.is_file() and digest(path) == expected
    passed = all(checks.values())
    print(json.dumps({"event": "TLS_PACKAGE_PASS" if passed else "TLS_PACKAGE_FAIL", "checks": checks,
                      "client_started": False, "binary_analysis_performed": False}, ensure_ascii=True))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
