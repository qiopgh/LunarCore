package emu.lunarcore.server.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.lunarcore.LunarCore;
import emu.lunarcore.proto.v450.candidate.Candidate450;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.SessionState;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import us.hebi.quickbuf.JsonSink;
import us.hebi.quickbuf.JsonSource;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;

/** 固定参考的有限候选适配；线格式仍由原 Quickbuf 与 BasePacket 处理。 */
public final class Release450Candidate {
    private static final JsonObject MANIFEST = loadManifest();
    private static final Map<Integer, String> REQUESTS = new HashMap<>();
    private static final Map<Integer, String> LEGACY_RESPONSES = new HashMap<>();
    private static final Map<String, Integer> COMMANDS = new HashMap<>();
    private static final Map<String, Class<?>> TYPES = new HashMap<>();
    private final GameSession session;
    private long loginRandom;
    private int propEntityId;
    private int lastEndStatus;
    private JsonObject currentBattleSnapshot;

    static {
        for (Class<?> type : Candidate450.class.getDeclaredClasses()) {
            if (ProtoMessage.class.isAssignableFrom(type)) TYPES.put(type.getSimpleName(), type);
        }
        for (var entry : MANIFEST.getAsJsonObject("cmdids").entrySet()) {
            int id = entry.getValue().getAsInt();
            String name = entry.getKey();
            COMMANDS.put(name, id);
            if (name.endsWith("CsReq")) REQUESTS.put(id, name);
            else {
                try {
                    int legacy = CmdId.class.getField(name).getInt(null);
                    if (legacy > 0) LEGACY_RESPONSES.put(legacy, name);
                } catch (ReflectiveOperationException ignored) {
                    // 新通知没有旧编号时只由具名生成消息发送，不猜旧别名。
                }
            }
        }
    }

    Release450Candidate(GameSession session) {
        this.session = session;
    }

    public static int command(String name) {
        Integer value = COMMANDS.get(name);
        if (value == null) throw new IllegalArgumentException("候选范围不包含消息：" + name);
        return value;
    }

    public static ProtoMessage<?> message(String name) {
        try {
            Class<?> type = TYPES.get(name);
            if (type == null) throw new IllegalArgumentException("候选范围不包含定义：" + name);
            return (ProtoMessage<?>) type.getMethod("newInstance").invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("候选生成类型无法构造：" + name, error);
        }
    }

    public static JsonObject json(ProtoMessage<?> message) throws Exception {
        try (JsonSink sink = JsonSink.newInstance().setWriteEnumsAsInts(true)) {
            message.writeTo(sink);
            return JsonParser.parseString(new String(sink.getBytes().toArray(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    public static ProtoMessage<?> fromJson(String name, JsonObject input) throws Exception {
        ProtoMessage<?> message = message(name);
        message.mergeFrom(JsonSource.newInstance(project(input, name).toString()));
        return message;
    }

    public static ProtoMessage<?> translate(String name, ProtoMessage<?> legacy) throws Exception {
        return fromJson(name, json(legacy));
    }

    // 只处理清单已登记的字段。字段同名映射仍是参考候选，不提升为正式语义证明。
    private static JsonObject project(JsonObject input, String name) {
        JsonObject normalized = input.deepCopy();
        if (name.equals("GetAvatarDataScRsp")) {
            alias(normalized, "avatarPathDataInfoList", "avatarPathInfoList");
            alias(normalized, "skinList", "ownedSkinList");
        }
        if (name.equals("AvatarPathData")) {
            alias(normalized, "avatarPathSkillTree", "avatarPathSkillTreeList");
            alias(normalized, "avatarPathSkillTree", "skilltreeList");
            alias(normalized, "dressedSkinId", "avatarSkin");
        }
        if (name.equals("Avatar")) {
            alias(normalized, "curMultiPathAvatarType", "changedAvatarType");
            alias(normalized, "firstMetTimeStamp", "firstMetTimestamp");
            alias(normalized, "hasTakenPromotionRewardList", "takenRewards");
        }
        if (name.equals("AvatarPathSkillTree") || name.equals("AvatarSkillTree")) {
            alias(normalized, "pointId", "anchorPointId");
        }
        if (name.equals("BattleAvatar")) {
            alias(normalized, "enhancedId", "enhanceId");
        }
        if (name.equals("SceneMonsterWave")) {
            alias(normalized, "battleWaveId", "waveId");
            alias(normalized, "battleStageId", "stageId");
            alias(normalized, "monsterParam", "waveParam");
        }
        if (name.equals("GateServer")) {
            alias(normalized, "baseAssetBundleVersionUpdateUrl", "baseAssetBundleUrl");
            normalized.addProperty("useTcp", false);
        }
        if (name.equals("SceneBattleInfo") && normalized.has("monsterWaveList")) {
            normalized.addProperty("monsterWaveLength", normalized.getAsJsonArray("monsterWaveList").size());
        }
        if (name.equals("SceneInfo") && !normalized.has("sceneIdentifier") && normalized.has("floorId")) {
            JsonObject identifier = new JsonObject();
            identifier.add("floorId", normalized.get("floorId"));
            normalized.add("sceneIdentifier", identifier);
        }
        JsonArray fields = MANIFEST.getAsJsonObject("messages").getAsJsonArray(name);
        if (fields == null) throw new IllegalArgumentException("缺少候选字段清单：" + name);
        JsonObject result = new JsonObject();
        for (JsonElement item : fields) {
            JsonObject field = item.getAsJsonObject();
            String key = camel(field.get("name").getAsString());
            if (!normalized.has(key)) continue;
            JsonElement value = normalized.get(key);
            String type = field.get("type").getAsString();
            if (MANIFEST.getAsJsonObject("messages").has(type)) {
                if (field.get("repeated").getAsBoolean()) {
                    JsonArray values = new JsonArray();
                    for (JsonElement nested : value.getAsJsonArray()) values.add(project(nested.getAsJsonObject(), type));
                    value = values;
                } else {
                    value = project(value.getAsJsonObject(), type);
                }
            }
            result.add(key, value);
        }
        return result;
    }

    private static String camel(String name) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char value : name.toCharArray()) {
            if (value == '_') upper = true;
            else {
                result.append(upper ? Character.toUpperCase(value) : value);
                upper = false;
            }
        }
        return result.toString();
    }

    private static void alias(JsonObject object, String target, String source) {
        if (!object.has(target) && object.has(source)) object.add(target, object.get(source));
    }

    public void receive(int cmdId, byte[] data, BiConsumer<Integer, byte[]> legacyDispatch) throws Exception {
        if (session.getAddress() == null || !session.getAddress().getAddress().isLoopbackAddress()) {
            session.diagnose("RECV", "CANDIDATE_NONLOCAL_REJECTED", cmdId, data.length, "LOOPBACK_ONLY");
            session.close();
            return;
        }
        String name = REQUESTS.get(cmdId);
        if (name == null) {
            session.diagnose("RECV", "CANDIDATE_UNMAPPED", cmdId, data.length, "NO_LEGACY_FALLBACK");
            return;
        }
        ProtoMessage<?> request = message(name);
        request.mergeFrom(ProtoSource.newInstance(data));
        JsonObject body = json(request);
        session.diagnose("RECV", "CANDIDATE_REQUEST", cmdId, data.length, name);
        if (name.equals("PlayerGetTokenCsReq")) {
            String accountUid = LunarCore.getConfig().getCandidate450().localAccountUid;
            if (accountUid == null || accountUid.isBlank()) {
                throw new IllegalStateException("候选配置必须显式绑定隔离本地账号");
            }
            // 正式认证字段仍未知；只向原 handler 传显式配置的本地测试身份。
            body.addProperty("accountUid", accountUid);
        } else if (name.equals("PlayerLoginCsReq")) {
            loginRandom = body.has("loginRandom") ? body.get("loginRandom").getAsLong() : 0;
        } else if (name.equals("GetCurBattleInfoCsReq")) {
            if (session.getState() != SessionState.ACTIVE) {
                session.diagnose("RECV", "STATE_REJECTED", cmdId, data.length, "EXPECTED_ACTIVE");
                return;
            }
            JsonObject response = new JsonObject();
            response.addProperty("retcode", 0);
            response.addProperty("lastEndStatus", lastEndStatus);
            if (session.getPlayer().getBattle() != null) {
                if (currentBattleSnapshot == null) currentBattleSnapshot = json(session.getPlayer().getBattle().toProto());
                response.add("battleInfo", currentBattleSnapshot.deepCopy());
            }
            reply("GetCurBattleInfoScRsp", response);
            return;
        } else if (name.equals("StartCocoonStageCsReq") || name.equals("QuickStartCocoonStageCsReq")) {
            alias(body, "waveCount", "wave");
            propEntityId = body.has("propEntityId") ? body.get("propEntityId").getAsInt() : 0;
        } else if (name.equals("PVEBattleResultCsReq")) {
            if (session.getState() != SessionState.ACTIVE) {
                session.diagnose("RECV", "STATE_REJECTED", cmdId, data.length, "EXPECTED_ACTIVE");
                return;
            }
            var battle = session.getPlayer().getBattle();
            int battleId = integer(body, "battleId");
            int stageId = integer(body, "stageId");
            int endStatus = integer(body, "endStatus");
            if (battle == null || battleId != battle.getId() || stageId != battle.getStage().getId()
                    || endStatus < 1 || endStatus > 3) {
                JsonObject rejection = new JsonObject();
                rejection.addProperty("retcode", 1);
                rejection.addProperty("battleId", battleId);
                rejection.addProperty("stageId", stageId);
                reply("PVEBattleResultScRsp", rejection);
                session.diagnose("RECV", "BATTLE_RESULT_REJECTED", cmdId, data.length, "IDENTITY_OR_STATE");
                return;
            }
            lastEndStatus = endStatus;
            // 参考的结算 HP/SP 字段仍混淆，不猜映射；避免把缺失属性作为零血写入存档。
            JsonObject stats = body.has("stt") ? body.getAsJsonObject("stt") : new JsonObject();
            stats.remove("battleAvatarList");
            body.add("stt", stats);
            session.diagnose("RECV", "CANDIDATE_HP_MAPPING_PENDING", cmdId, data.length, "PRESERVE_EXISTING_HP_SP");
        }
        int legacyId;
        try {
            legacyId = CmdId.class.getField(name).getInt(null);
        } catch (ReflectiveOperationException missing) {
            session.diagnose("RECV", "CANDIDATE_NO_BUSINESS_HANDLER", cmdId, data.length, name);
            return;
        }
        byte[] legacyBody = new byte[0];
        if (name.equals("PlayerGetTokenCsReq") || name.equals("PlayerHeartBeatCsReq")
                || name.equals("StartCocoonStageCsReq") || name.equals("QuickStartCocoonStageCsReq")
                || name.equals("PVEBattleResultCsReq") || name.equals("SceneEntityMoveCsReq")) {
            Class<?> legacyType = Class.forName("emu.lunarcore.proto." + name + "OuterClass$" + name);
            ProtoMessage<?> legacy = (ProtoMessage<?>) legacyType.getMethod("newInstance").invoke(null);
            legacy.mergeFrom(JsonSource.newInstance(body.toString()).setIgnoreUnknownFields(true));
            legacyBody = legacy.toByteArray();
        }
        legacyDispatch.accept(legacyId, legacyBody);
        if (name.equals("PVEBattleResultCsReq") && session.getPlayer().getBattle() == null) currentBattleSnapshot = null;
    }

    public BasePacket outgoing(BasePacket packet) throws Exception {
        ProtoMessage<?> payload = packet.getData();
        if (payload != null && payload.getClass().getEnclosingClass() == Candidate450.class) return packet;
        String name = packet.getClass().getSimpleName().replaceFirst("^Packet", "");
        if (!COMMANDS.containsKey(name) && payload != null) name = payload.getClass().getSimpleName();
        if (name.equals("StaminaInfoScNotify") && session.getPlayer() != null) {
            JsonObject data = new JsonObject();
            data.add("basicInfo", json(session.getPlayer().toProto()));
            return packet("PlayerSyncScNotify", data);
        }
        if (!COMMANDS.containsKey(name)) {
            session.diagnose("SEND", "CANDIDATE_UNMAPPED", packet.getCmdId(), 0, name);
            return null;
        }
        JsonObject data = payload == null ? new JsonObject() : json(payload);
        if (name.equals("PlayerLoginScRsp")) data.addProperty("loginRandom", loginRandom);
        if (name.equals("StartCocoonStageScRsp")) data.addProperty("propEntityId", propEntityId);
        if ((name.equals("StartCocoonStageScRsp") || name.equals("QuickStartCocoonStageScRsp")) && data.has("battleInfo")) {
            // 固定本场已发送的正文，查询战斗时不再次生成随机种子。
            currentBattleSnapshot = data.getAsJsonObject("battleInfo").deepCopy();
        }
        return packet(name, data);
    }

    public BasePacket empty(int legacyCmdId) throws Exception {
        String name = LEGACY_RESPONSES.get(legacyCmdId);
        if (name == null) {
            session.diagnose("SEND", "CANDIDATE_UNMAPPED", legacyCmdId, 0, "EMPTY_PACKET");
            return null;
        }
        return packet(name, new JsonObject());
    }

    public static BasePacket packet(String name, JsonObject body) throws Exception {
        BasePacket packet = new BasePacket(command(name));
        packet.setData(fromJson(name, body));
        return packet;
    }

    private void reply(String name, JsonObject body) throws Exception {
        session.send(packet(name, body));
    }

    private static int integer(JsonObject body, String name) {
        return body.has(name) ? body.get(name).getAsInt() : 0;
    }

    private static JsonObject loadManifest() {
        var stream = Release450Candidate.class.getResourceAsStream("/release450-candidate/schema.json");
        if (stream == null) throw new IllegalStateException("候选 schema 来源清单缺失");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception error) {
            throw new IllegalStateException("候选 schema 来源清单无效", error);
        }
    }
}
