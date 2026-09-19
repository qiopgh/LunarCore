from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path

def parse_pattern(text: str) -> list[int | None]:
    return [None if part == "??" else int(part, 16) for part in text.split()]


def find_all(data: bytes, pattern: list[int | None]) -> list[int]:
    width = len(pattern)
    return [
        offset
        for offset in range(0, len(data) - width + 1)
        if all(expected is None or data[offset + index] == expected for index, expected in enumerate(pattern))
    ]


def offset_to_rva(data: bytes, offset: int) -> int:
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe_offset : pe_offset + 4] != b"PE\0\0":
        raise ValueError("PE_SIGNATURE_MISSING")
    section_count = struct.unpack_from("<H", data, pe_offset + 6)[0]
    optional_size = struct.unpack_from("<H", data, pe_offset + 20)[0]
    section_table = pe_offset + 24 + optional_size
    for index in range(section_count):
        entry = section_table + index * 40
        virtual_size, virtual_address, raw_size, raw_offset = struct.unpack_from("<IIII", data, entry + 8)
        if raw_offset <= offset < raw_offset + raw_size:
            return virtual_address + offset - raw_offset
        if raw_size == 0 and raw_offset <= offset < raw_offset + virtual_size:
            return virtual_address + offset - raw_offset
    raise ValueError(f"PE_OFFSET_NOT_MAPPED:{offset:#x}")


def main() -> int:
    parser = argparse.ArgumentParser(description="核对固定 SDK DLL 的哈希、唯一模式与 rel32 目标。")
    parser.add_argument("bindings", type=Path)
    parser.add_argument("client_root", type=Path)
    args = parser.parse_args()

    bindings = json.loads(args.bindings.read_text(encoding="utf-8"))
    failures = 0
    for binding in bindings:
        path = args.client_root / binding["relative_path"]
        data = path.read_bytes()
        actual_hash = hashlib.sha256(data).hexdigest()
        hash_ok = actual_hash.lower() == binding["sha256"].lower()
        pattern = parse_pattern(binding["pattern"])
        matches = find_all(data, pattern)
        match_rvas = [offset_to_rva(data, offset) for offset in matches]
        expected_match = int(binding["match_rva"], 16)
        expected_target = int(binding["constructor_rva"], 16)
        actual_target = None
        if len(matches) == 1 and binding.get("target_kind", "rel32") == "direct":
            actual_target = match_rvas[0]
        elif len(matches) == 1 and data[matches[0]] == 0xE8:
            displacement = struct.unpack_from("<i", data, matches[0] + 1)[0]
            actual_target = match_rvas[0] + 5 + displacement
        passed = hash_ok and match_rvas == [expected_match] and actual_target == expected_target
        print(
            "SDK_BINDING "
            f"NAME={binding['name']} HASH={hash_ok} MATCHES={len(matches)} "
            f"MATCH_RVA={','.join(hex(value) for value in match_rvas) or 'none'} "
            f"TARGET_RVA={hex(actual_target) if actual_target is not None else 'none'} PASS={passed}"
        )
        failures += 0 if passed else 1

    print(f"SDK_BINDINGS_CHECK PASS={failures == 0} COUNT={len(bindings)}")
    return 0 if failures == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
