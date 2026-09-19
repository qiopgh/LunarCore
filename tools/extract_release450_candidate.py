"""从已固定的 Himeko 描述表提取最小链投影；不推断正式构建的未知字段。"""

import argparse
import hashlib
import json
import pathlib
import re


ROOTS = [
    "Dispatch", "GateServer", "PlayerGetTokenCsReq", "PlayerGetTokenScRsp",
    "PlayerLoginCsReq", "PlayerLoginScRsp", "PlayerLoginFinishCsReq", "PlayerLoginFinishScRsp",
    "PlayerHeartBeatCsReq", "PlayerHeartBeatScRsp", "GetAvatarDataCsReq", "GetAvatarDataScRsp",
    "GetCurLineupDataCsReq", "GetCurLineupDataScRsp", "GetAllLineupDataCsReq", "GetAllLineupDataScRsp",
    "GetCurSceneInfoCsReq", "GetCurSceneInfoScRsp", "SceneEntityMoveCsReq", "SceneEntityMoveScRsp",
    "StartCocoonStageCsReq", "StartCocoonStageScRsp", "QuickStartCocoonStageCsReq", "QuickStartCocoonStageScRsp",
    "PVEBattleResultCsReq", "PVEBattleResultScRsp", "SyncLineupNotify", "PlayerSyncScNotify",
    "GetBasicInfoCsReq", "GetBasicInfoScRsp", "ContentPackageGetDataCsReq", "ContentPackageGetDataScRsp",
    "ContentPackageSyncDataScNotify", "GetCurBattleInfoCsReq", "GetCurBattleInfoScRsp",
]

# 限定固定角色/场景/普通茧切片，不把无关玩法的依赖闭包带入。
FIELDS = {
    "PlayerGetTokenCsReq": "platform uid",
    "PlayerGetTokenScRsp": "uid retcode",
    "PlayerLoginCsReq": "login_random platform client_res_version",
    "PlayerLoginScRsp": "basic_info server_timestamp_ms cur_timezone login_random retcode stamina",
    "PlayerHeartBeatCsReq": "client_time_ms",
    "PlayerHeartBeatScRsp": "server_time_ms client_time_ms retcode",
    "GetAvatarDataScRsp": "retcode is_get_all avatar_list skin_list avatar_path_data_info_list basic_type_id_list",
    "GetBasicInfoScRsp": "gender is_gender_set next_recover_time week_cocoon_finished_count cur_day gameplay_birthday exchange_times retcode last_set_nickname_time",
    "PlayerSyncScNotify": "basic_info material_list",
    "SceneInfo": "entry_id floor_id plane_id leader_entity_id world_id game_mode_type entity_list entity_group_list scene_identifier",
    "SceneEntityInfo": "entity_id inst_id motion group_id actor npc_monster npc prop",
    "ScenePropInfo": "prop_state prop_id life_time_ms create_time_ms",
    "SceneNpcMonsterInfo": "monster_id event_id world_level",
    "SceneNpcInfo": "npc_id",
    "SceneBattleInfo": "monster_wave_length battle_id stage_id logic_random_seed buff_list monster_wave_list world_level battle_avatar_list rounds_limit",
    "BattleStatistics": "total_battle_turns total_auto_turns avatar_id_list ultra_cnt total_delay_cumulate cost_time battle_avatar_list round_cnt cocoon_dead_wave avatar_battle_turns monster_battle_turns challenge_score end_reason",
    "AvatarBattleInfo": "id avatar_type stage_id total_damage",
    "PVEBattleResultCsReq": "stt client_res_version battle_id end_status stage_id",
    "PVEBattleResultScRsp": "end_status retcode drop_data battle_avatar_list multiple_drop_data stage_id battle_id",
    "StartCocoonStageCsReq": "world_level wave prop_entity_id cocoon_id",
    "QuickStartCocoonStageCsReq": "world_level wave cocoon_id",
    "GateServer": "ip port region_name asset_bundle_url base_asset_bundle_version_update_url ex_resource_url lua_url ifix_url ifix_version use_tcp",
}

SCALARS = {"u32": "uint32", "u64": "uint64", "i32": "int32", "i64": "int64", "bool": "bool", "f32": "float", "f64": "double"}
OBFUSCATED = re.compile(r"[A-Z]{10,}")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def extract(reference):
    protocol = reference / "protocol/src/protocol.pb.zig"
    text = protocol.read_text(encoding="utf-8")
    blocks = {}
    for match in re.finditer(r"^pub const (\w+) = (struct|enum\([^)]+\)) \{", text, re.M):
        end = text.index("\n};", match.end())
        blocks[match.group(1)] = (match.group(2), text[match.end():end])
    # 参考 handler 不读取此请求正文，投影因此不消费任何字段；并非恢复完整请求。
    blocks["GetBasicInfoCsReq"] = ("struct", "\n    pub const _desc_table = .{};\n    pub usingnamespace")
    emitted, rows, omitted = {}, {}, {}

    def visit(name):
        if name in emitted:
            return
        kind, block = blocks[name]
        emitted[name] = ""
        if kind.startswith("enum"):
            values = re.findall(r"^    (\w+) = (-?\d+),", block, re.M)
            assert any(int(value) == 0 for _, value in values), name
            values.sort(key=lambda item: int(item[1]) != 0)
            lines = [f"enum {name} {{"]
            if len({value for _, value in values}) != len(values):
                lines.append("  option allow_alias = true;")
            lines += [f"  {name}_{key} = {value};" for key, value in values]
            emitted[name] = "\n".join(lines + ["}"])
            return
        head = block[:block.index("    pub usingnamespace")]
        declarations = dict(re.findall(r"^    (\w+): ([^=,\n]+?)(?: = [^\n]*)?,?$", head, re.M))
        descriptors = {field: (int(number), description) for field, number, description in re.findall(r"^        \.(\w+) = fd\((\d+), (.*)\),$", head, re.M)}
        oneofs = {}
        for union_name, union_block in re.findall(r"pub const (\w+) = union\([^)]*\) \{(.*?)\n    \};", head, re.S):
            union_types = dict(re.findall(r"^        (\w+): ([\w.]+),$", union_block, re.M))
            for field, number, description in re.findall(r"^            \.(\w+) = fd\((\d+), (.*)\),$", union_block, re.M):
                declarations[field] = union_types[field]
                descriptors[field] = (int(number), description)
                oneofs[field] = union_name.removesuffix("_union")
        allowed = set(FIELDS[name].split()) if name in FIELDS else None
        lines, groups, selected, dropped = [], {}, [], []
        for field, (number, description) in descriptors.items():
            raw_type = declarations[field].strip().removesuffix(",")
            repeated = raw_type.startswith("ArrayList(")
            type_name = raw_type[10:-1] if repeated else raw_type.removeprefix("?")
            if (allowed is not None and field not in allowed) or OBFUSCATED.fullmatch(field) or OBFUSCATED.fullmatch(type_name) or field.startswith("unk_"):
                dropped.append(field)
                continue
            if type_name == "ManagedString":
                proto_type = "bytes" if ".Bytes" in description else "string"
            elif type_name in SCALARS:
                proto_type = SCALARS[type_name]
                if "ZigZag" in description:
                    proto_type = {"i32": "sint32", "i64": "sint64"}[type_name]
                elif "FixedInt" in description and type_name in {"u32", "u64", "i32", "i64"}:
                    proto_type = {"u32": "fixed32", "u64": "fixed64", "i32": "sfixed32", "i64": "sfixed64"}[type_name]
            else:
                if "." in type_name:
                    owner, nested = type_name.split(".", 1)
                    nested_match = re.search(r"    pub const " + re.escape(nested) + r" = struct \{(.*?)\n    \};", blocks[owner][1], re.S)
                    assert nested_match, type_name
                    flat = owner + "_" + nested
                    nested_body = "\n".join(line[4:] if line.startswith("    ") else line for line in nested_match.group(1).splitlines())
                    blocks[flat] = ("struct", nested_body)
                    type_name = flat
                visit(type_name)
                proto_type = type_name
            option = ""
            if repeated and (type_name in SCALARS or type_name in blocks and blocks[type_name][0].startswith("enum")):
                option = " [packed = true]" if "PackedList" in description else " [packed = false]"
            declaration = f"{'repeated ' if repeated else ''}{proto_type} {field} = {number}{option};"
            if field in oneofs:
                groups.setdefault(oneofs[field], []).append("    " + declaration)
            else:
                lines.append("  " + declaration)
            selected.append({"name": field, "number": number, "type": proto_type, "repeated": repeated, "descriptor": description, "oneof": oneofs.get(field)})
        if allowed is not None:
            missing = allowed - set(descriptors)
            assert not missing, (name, missing)
        for group, members in groups.items():
            lines.extend(["  oneof " + group + " {", *members, "  }"])
        emitted[name] = "\n".join([f"message {name} {{", *lines, "}"])
        rows[name], omitted[name] = selected, dropped

    for name in ROOTS:
        visit(name)
    schema = '\n'.join([
        '// 从固定参考描述表提取的候选投影；不是正式客户端已验证的完整协议。',
        'syntax = "proto3";', 'package lunarcore.release450.candidate;',
        'option java_package = "emu.lunarcore.proto.v450.candidate";',
        'option java_outer_classname = "Candidate450";', '',
        *[emitted[name] + "\n" for name in sorted(emitted)],
    ])
    commands = dict((name, int(number)) for name, number in re.findall(r"Cmd(\w+) = (\d+),", (reference / "protocol/src/root.zig").read_text(encoding="utf-8")))
    manifest = {
        "source": "https://git.xeondev.com/HonkaiSlopRail/himeko-nova-sr",
        "reference_commit": "3b304b5a1b1cc0d478e285c3dcb0d4a555ed0910",
        "reference_declared_client": "4.5 beta",
        "source_sha256": digest(protocol),
        "cmd_source_sha256": digest(reference / "protocol/src/root.zig"),
        "target_client_build": "20260813-0422-V4.5Live-16121062-OSPRODWin4.5.0-OSLive",
        "evidence_level": "REFERENCE_CANDIDATE_NOT_OFFICIAL_WIRE_ACCEPTANCE",
        "roots": ROOTS, "messages": rows, "omitted_fields": omitted,
        "empty_request_projection": {"GetBasicInfoCsReq": "services/avatar.zig:onGetBasicInfo 不读取请求正文；仅声明零字段消费投影。"},
        "cmdids": {name: commands[name] for name in ROOTS if name in commands},
        "official_cmdid_anchors": {"StartCocoonStageScRsp": 1479},
        "schema_sha256": hashlib.sha256(schema.encode("utf-8")).hexdigest(),
        "license": "参考未明确许可证；保留来源与边界，不声称取得额外授权。",
    }
    return schema, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference-root", type=pathlib.Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    schema, manifest = extract(args.reference_root)
    outputs = {
        root / "src/release450/proto/candidate_chain.proto": schema.encode("utf-8"),
        root / "src/main/resources/release450-candidate/schema.json": (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    }
    for path, data in outputs.items():
        if args.check:
            assert path.read_bytes() == data, path
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    print(json.dumps({"schema_sha256": manifest["schema_sha256"], "messages": len(manifest["messages"]), "cmdids": len(manifest["cmdids"]), "mode": "check" if args.check else "write"}))


if __name__ == "__main__":
    main()
