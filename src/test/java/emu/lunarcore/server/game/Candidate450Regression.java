package emu.lunarcore.server.game;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.proto.PlayerGetTokenCsReqOuterClass.PlayerGetTokenCsReq;
import emu.lunarcore.proto.StartCocoonStageCsReqOuterClass.StartCocoonStageCsReq;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;
import emu.lunarcore.server.packet.SessionState;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import us.hebi.quickbuf.ProtoMessage;
import us.hebi.quickbuf.ProtoSource;

/** 原收包与分发器加候选投影回归；合成业务处理器不代表真实登录验收。 */
public final class Candidate450Regression {
    private static int passed;
    private static int calls;
    private static byte[] received;

    public static void main(String[] args) throws Exception {
        Config config = LunarCore.getConfig();
        config.logOptions.connections = false;
        config.logOptions.packets = false;
        config.logOptions.sessionDiagnostics = true;
        config.candidate450.enabled = false;
        Logger logger = (Logger) LunarCore.getLogger();
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            testConfiguration();
            testOriginalFrameIngress(logs);
            testStateConditions();
            testCandidateSequence(logs);
            testProjections();
            if (Arrays.asList(args).contains("--negative-control")) require(false, "故意失败的负面控制");
            System.out.println("CANDIDATE450_TESTS_PASSED=" + passed);
            System.out.println("BOUNDARY=候选映射与原收包/分发离线验证；未启动服务或客户端，不证明正式认证或客户端兼容");
        } finally {
            logger.detachAppender(logs);
            config.candidate450.enabled = false;
        }
    }

    private static void testConfiguration() {
        Config config = new Config();
        require(!config.candidate450.enabled && !config.logOptions.sessionDiagnostics, "候选及诊断应默认关闭");
        config.candidate450.enabled = true;
        expectFailure(config::validate, "候选拒绝默认公网绑定");
        config.gameServer.bindAddress = "127.0.0.1";
        config.httpServer.bindAddress = "127.0.0.1";
        expectFailure(config::validate, "候选拒绝未绑定账号");
        config.candidate450.localAccountUid = "FIXTURE_ACCOUNT";
        config.validate();
        require(true, "回环且显式账号配置可用");
    }

    private static void testOriginalFrameIngress(ListAppender<ILoggingEvent> logs) throws Exception {
        RecordingSession session = new RecordingSession(false);
        BasePacket packet = new BasePacket(40000);
        packet.setData(new byte[]{0x68, 0x01});
        byte[] frame = packet.build();
        feed(session, frame);
        require(session.opcodes.equals(List.of(40000)) && Arrays.equals(session.bodies.get(0), new byte[]{0x68, 0x01}), "无符号编号与正文原样分发");
        session.opcodes.clear();
        var joined = Unpooled.wrappedBuffer(frame, frame);
        try { session.onMessage(joined); } finally { joined.release(); }
        require(session.opcodes.size() == 2, "同一缓冲区两个完整帧");
        var direct = Unpooled.directBuffer().writeBytes(frame);
        try { session.onMessage(direct); } finally { direct.release(); }
        require(session.opcodes.size() == 3, "直接缓冲区仍由原入口解析");
        checkRejected(session, Arrays.copyOf(frame, 7), "TRUNCATED_FRAME", logs);
        byte[] badHeader = frame.clone(); badHeader[0] ^= 1;
        checkRejected(session, badHeader, "BAD_HEADER", logs);
        byte[] badTail = frame.clone(); badTail[badTail.length - 1] ^= 1;
        checkRejected(session, badTail, "BAD_TAIL", logs);
        byte[] negative = frame.clone(); ByteBuffer.wrap(negative).putInt(8, -1);
        checkRejected(session, negative, "INVALID_LENGTH", logs);
        byte[] huge = frame.clone(); ByteBuffer.wrap(huge).putInt(8, Integer.MAX_VALUE);
        checkRejected(session, huge, "TRUNCATED_BODY", logs);
        byte[] extended = ByteBuffer.allocate(16 + 32768 + 2).putInt(BasePacket.HEADER_CONST).putShort((short) 1479)
                .putShort((short) 32768).putInt(2).put(new byte[32768]).put(new byte[]{0x68, 0x01}).putInt(BasePacket.TAIL_CONST).array();
        feed(session, extended);
        require(Arrays.equals(session.bodies.get(session.bodies.size() - 1), new byte[]{0x68, 0x01}), "无符号扩展头长度");
        logs.list.clear();
        session.send(new BasePacket(-1));
        session.send(0);
        require(events(logs, "MISSING_CMDID") == 2, "两条缺编号发送路径可诊断");
        session.send(new BasePacket(1479));
        require(events(logs, "NO_TRANSPORT") == 1, "没有传输对象不能被写成已发出");
        logs.list.clear();
        LunarCore.getConfig().logOptions.sessionDiagnostics = false;
        feed(session, negative);
        require(logs.list.isEmpty(), "诊断开关关闭时不产生日志");
        LunarCore.getConfig().logOptions.sessionDiagnostics = true;
    }

    private static void testStateConditions() {
        for (SessionState state : SessionState.values()) {
            require(GameServerPacketHandler.rejectionReason(state, CmdId.PlayerHeartBeatCsReq) == null, "原心跳放行规则：" + state);
            require((GameServerPacketHandler.rejectionReason(state, CmdId.PlayerGetTokenCsReq) == null) == (state == SessionState.WAITING_FOR_TOKEN), "原 token 状态规则：" + state);
            require((GameServerPacketHandler.rejectionReason(state, CmdId.PlayerLoginCsReq) == null) == (state == SessionState.WAITING_FOR_LOGIN), "原 login 状态规则：" + state);
            require((GameServerPacketHandler.rejectionReason(state, 60000) == null) == (state == SessionState.ACTIVE), "原普通消息状态规则：" + state);
        }
    }

    private static void testCandidateSequence(ListAppender<ILoggingEvent> logs) throws Exception {
        Config config = LunarCore.getConfig();
        config.candidate450.enabled = true;
        config.candidate450.localAccountUid = "FIXTURE_ACCOUNT";
        RecordingSession session = new RecordingSession(true);
        session.router.registerPacketHandler(TokenHandler.class);
        session.router.registerPacketHandler(LoginHandler.class);
        session.router.registerPacketHandler(CocoonHandler.class);
        session.router.registerPacketHandler(ExceptionHandler.class);
        calls = 0;
        feed(session, Release450Candidate.packet("PlayerLoginCsReq", object("{\"loginRandom\":42}")).build());
        require(calls == 0 && session.getState() == SessionState.WAITING_FOR_TOKEN, "候选乱序 login 被原分发器拒绝");
        feed(session, Release450Candidate.packet("PlayerGetTokenCsReq", object("{\"uid\":999,\"platform\":2}")).build());
        require(calls == 1 && session.getState() == SessionState.WAITING_FOR_LOGIN, "候选 token 经过原分发器");
        require(PlayerGetTokenCsReq.parseFrom(received).getAccountUid().equals("FIXTURE_ACCOUNT"), "只绑定配置的本地身份，不猜认证混淆字段");
        feed(session, Release450Candidate.packet("PlayerLoginCsReq", object("{\"loginRandom\":9223372036854770000}")).build());
        require(calls == 2 && session.getState() == SessionState.ACTIVE, "候选 login 状态前进");
        var oldLogin = emu.lunarcore.proto.PlayerLoginScRspOuterClass.PlayerLoginScRsp.newInstance().setStamina(300);
        BasePacket legacy = new BasePacket(CmdId.PlayerLoginScRsp); legacy.setData(oldLogin);
        BasePacket encoded = session.getCandidate450().outgoing(legacy);
        JsonObject login = Release450Candidate.json(encoded.getData());
        require(encoded.getCmdId() == 13 && login.get("loginRandom").getAsLong() == 9223372036854770000L, "候选响应编号与 64 位 login_random 回显");
        feed(session, Release450Candidate.packet("StartCocoonStageCsReq", object("{\"cocoonId\":1001,\"worldLevel\":0,\"wave\":1,\"propEntityId\":123}")).build());
        var cocoon = StartCocoonStageCsReq.parseFrom(received);
        require(calls == 3 && cocoon.getCocoonId() == 1001 && cocoon.getWaveCount() == 1, "候选 wave 转入原业务 waveCount");
        logs.list.clear();
        int before = calls;
        feed(session, new BasePacket(65000).build());
        require(calls == before && events(logs, "CANDIDATE_UNMAPPED") == 1, "未知候选编号不回退旧协议");
        session.remote = new InetSocketAddress("192.0.2.1", 1);
        feed(session, Release450Candidate.packet("PlayerGetTokenCsReq", new JsonObject()).build());
        require(calls == before && events(logs, "CANDIDATE_NONLOCAL_REJECTED") == 1, "候选拒绝非回环会话");
        config.candidate450.enabled = false;
        RecordingSession plain = new RecordingSession(true);
        plain.setState(SessionState.ACTIVE);
        plain.router.registerPacketHandler(ExceptionHandler.class);
        logs.list.clear();
        BasePacket secret = new BasePacket(60001); secret.setData("SYNTHETIC_SECRET".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        feed(plain, secret.build());
        require(events(logs, "HANDLER_EXCEPTION") == 1, "handler 异常有分类");
        require(logs.list.stream().noneMatch(event -> event.getFormattedMessage().contains("SYNTHETIC_SECRET") || event.getFormattedMessage().contains("53594e544845544943")), "诊断不记录正文或异常中的秘密值");
    }

    private static void testProjections() throws Exception {
        ProtoMessage<?> avatar = Release450Candidate.fromJson("GetAvatarDataScRsp", object("{\"isGetAll\":true,\"ownedSkinList\":[1],\"avatarList\":[{\"baseAvatarId\":8001,\"changedAvatarType\":8001,\"firstMetTimestamp\":10}],\"avatarPathInfoList\":[{\"avatarId\":8001,\"avatarSkin\":0,\"skilltreeList\":[{\"anchorPointId\":800101,\"level\":1}]}]}"));
        JsonObject data = Release450Candidate.json(avatar);
        require(data.getAsJsonArray("avatarList").get(0).getAsJsonObject().get("curMultiPathAvatarType").getAsInt() == 8001, "多命途角色字段转换");
        require(data.getAsJsonArray("avatarPathDataInfoList").get(0).getAsJsonObject().getAsJsonArray("avatarPathSkillTree").get(0).getAsJsonObject().get("pointId").getAsInt() == 800101, "角色技能树依赖转换");
        JsonObject battle = object("{\"battleId\":7,\"stageId\":1022010,\"logicRandomSeed\":1234,\"worldLevel\":0,\"monsterWaveList\":[{\"waveId\":1,\"stageId\":1022010,\"waveParam\":{\"level\":1},\"monsterList\":[{\"monsterId\":1022010}]}],\"battleAvatarList\":[{\"id\":8001,\"avatarType\":3,\"level\":1,\"hp\":10000,\"spBar\":{\"curSp\":0,\"maxSp\":12000}}]}");
        JsonObject response = new JsonObject(); response.add("battleInfo", battle); response.addProperty("cocoonId", 1001); response.addProperty("wave", 1);
        var frame = Release450Candidate.packet("StartCocoonStageScRsp", response);
        JsonObject converted = Release450Candidate.json(frame.getData()).getAsJsonObject("battleInfo");
        require(converted.getAsJsonArray("monsterWaveList").get(0).getAsJsonObject().get("battleStageId").getAsInt() == 1022010, "怪物波次与阶段转换");
        require(converted.get("monsterWaveLength").getAsInt() == 1, "显式波次数量");
        ProtoMessage<?> roundtrip = Release450Candidate.message("StartCocoonStageScRsp");
        roundtrip.mergeFrom(ProtoSource.newInstance(frame.getData().toByteArray()));
        require(Release450Candidate.json(roundtrip).equals(Release450Candidate.json(frame.getData())), "成功战斗正文原 Quickbuf 往返");
        require(ByteBuffer.wrap(frame.build()).getShort(4) == 1479, "仍用原 BasePacket 输出已确认的响应编号");
        var gate = Release450Candidate.fromJson("GateServer", object("{\"ip\":\"127.0.0.1\",\"port\":23301,\"baseAssetBundleUrl\":\"file://fixture\",\"unk1\":true}"));
        JsonObject gateway = Release450Candidate.json(gate);
        require(!gateway.get("useTcp").getAsBoolean() && !gateway.has("unk1"), "网关保留原生 KCP，不猜旧未知开关");
        require(gateway.get("baseAssetBundleVersionUpdateUrl").getAsString().equals("file://fixture"), "网关资源地址具名候选映射");
        expectFailure(() -> Release450Candidate.message("NotAnImplementedMessage"), "未实现消息不伪造空成功");
    }

    private static void checkRejected(RecordingSession session, byte[] frame, String event, ListAppender<ILoggingEvent> logs) {
        int before = session.opcodes.size(); logs.list.clear(); feed(session, frame);
        require(session.opcodes.size() == before && events(logs, event) == 1, "非法输入分类：" + event);
    }

    private static long events(ListAppender<ILoggingEvent> logs, String event) {
        return logs.list.stream().filter(value -> value.getFormattedMessage().contains("event=" + event + " ")).count();
    }

    private static void feed(GameSession session, byte[] bytes) {
        var input = Unpooled.wrappedBuffer(bytes);
        try { session.onMessage(input); } finally { input.release(); }
    }

    private static JsonObject object(String json) { return JsonParser.parseString(json).getAsJsonObject(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed++;
        System.out.println("PASS=" + message);
    }
    private static void expectFailure(Runnable work, String message) {
        try { work.run(); } catch (IllegalArgumentException expected) { require(true, message); return; }
        throw new AssertionError(message);
    }

    private static final class RecordingSession extends GameSession {
        final List<Integer> opcodes = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final GameServerPacketHandler router = new GameServerPacketHandler(false);
        final boolean route;
        InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 1);
        RecordingSession(boolean route) { super(null); this.route = route; }
        @Override public InetSocketAddress getAddress() { return remote; }
        @Override protected void handlePacket(int cmdId, byte[] body) {
            if (route) router.handle(this, cmdId, body);
            else { opcodes.add(cmdId); bodies.add(body); }
        }
    }
    @Opcodes(CmdId.PlayerGetTokenCsReq)
    public static final class TokenHandler extends PacketHandler {
        @Override public void handle(GameSession session, byte[] body) { calls++; received = body; session.setState(SessionState.WAITING_FOR_LOGIN); }
    }
    @Opcodes(CmdId.PlayerLoginCsReq)
    public static final class LoginHandler extends PacketHandler {
        @Override public void handle(GameSession session, byte[] body) { calls++; received = body; session.setState(SessionState.ACTIVE); }
    }
    @Opcodes(CmdId.StartCocoonStageCsReq)
    public static final class CocoonHandler extends PacketHandler {
        @Override public void handle(GameSession session, byte[] body) { calls++; received = body; }
    }
    @Opcodes(60001)
    public static final class ExceptionHandler extends PacketHandler {
        @Override public void handle(GameSession session, byte[] body) { throw new IllegalArgumentException("SYNTHETIC_SECRET"); }
    }
}
