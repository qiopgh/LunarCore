package emu.lunarcore.server.game;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ContentPackageExcel;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.recv.HandlerPlayerLoginFinishCsReq;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import us.hebi.quickbuf.ProtoSource;

/** 原收包/分发/资源构造器的最小初始化检查，不启动服务或客户端。 */
public final class LoginFinishInitializationRegression {
    private static int passed;

    public static void main(String[] args) throws Exception {
        int result = inspect();
        if (result < 0) System.exit(2);
        System.out.println("LOGIN_FINISH_TESTS_PASSED=" + result);
    }

    public static int verify() throws Exception {
        int result = inspect();
        if (result < 0) throw new AssertionError("候选LoginFinish缺少参考初始化顺序");
        return result;
    }

    private static int inspect() throws Exception {
        passed = 0;
        var config = LunarCore.getConfig();
        boolean originalEnabled = config.candidate450.enabled;
        boolean originalPackets = config.logOptions.packets;
        boolean originalDiagnostics = config.logOptions.sessionDiagnostics;
        var content = GameData.getContentPackageExcelMap();
        var originalContent = new ArrayList<>(content.values());
        config.candidate450.enabled = true;
        config.logOptions.packets = false;
        config.logOptions.sessionDiagnostics = false;
        try {
            content.values().clear();
            content.add(new Gson().fromJson("{\"ContentID\":200001}", ContentPackageExcel.class));
            var session = new RecordingSession(true);
            session.setState(SessionState.ACTIVE);
            byte[] input = Release450Candidate.packet("PlayerLoginFinishCsReq", new JsonObject()).build();
            feed(session, input);
            System.out.println("INPUT=ACTIVE loopback PlayerLoginFinishCsReq; one synthetic content id=200001");
            System.out.println("LOGIN_FINISH_SENT=" + session.sentOpcodes);
            if (!session.sentOpcodes.equals(List.of(7503, 36))) {
                System.out.println("LOGIN_FINISH_INITIALIZATION=false");
                return -1;
            }
            require(session.errors.isEmpty(), "初始化没有编码或处理异常");
            JsonObject sync = decode("ContentPackageSyncDataScNotify", session.sentBodies.get(0));
            JsonObject data = sync.getAsJsonObject("data");
            require(data.get("curContentId").getAsInt() == 200001, "沿用原资源构造器的当前内容编号");
            var list = data.getAsJsonArray("contentPackageList");
            require(list.size() == 1 && list.get(0).getAsJsonObject().get("contentId").getAsInt() == 200001, "同步通知沿用原内容资源列表");
            JsonObject done = decode("PlayerLoginFinishScRsp", session.sentBodies.get(1));
            require(!done.has("retcode") || done.get("retcode").getAsInt() == 0, "原LoginFinish handler仍给出候选成功响应");
            require(session.getState() == SessionState.ACTIVE, "不伪造新的客户端状态迁移");
            System.out.println("CONTENT_NOTIFY_BODY_HEX=" + HexFormat.of().formatHex(session.sentBodies.get(0)));
            session.clearSent();
            feed(session, input);
            require(session.sentOpcodes.equals(List.of(7503, 36)), "重复请求只重发相同阶段的通知与应答");
            require(content.size() == 1 && content.containsKey(200001), "初始化不修改资源表");
            for (SessionState state : List.of(SessionState.WAITING_FOR_TOKEN, SessionState.WAITING_FOR_LOGIN, SessionState.INACTIVE)) {
                session.clearSent();
                session.setState(state);
                feed(session, input);
                require(session.sentOpcodes.isEmpty(), "原状态拒绝前不得发送初始化：" + state);
            }
            session.clearSent();
            session.setState(SessionState.ACTIVE);
            BasePacket malformed = new BasePacket(Release450Candidate.command("PlayerLoginFinishCsReq"));
            malformed.setData(new byte[]{(byte) 0x80});
            feed(session, malformed.build());
            require(session.sentOpcodes.isEmpty(), "正文解析失败不得先发送初始化");
            session.clearSent();
            session.remote = new InetSocketAddress("192.0.2.1", 1);
            feed(session, input);
            require(session.closed && session.sentOpcodes.isEmpty(), "非回环拒绝仍早于初始化");
            config.candidate450.enabled = false;
            var legacy = new RecordingSession(true);
            legacy.setState(SessionState.ACTIVE);
            feed(legacy, new BasePacket(CmdId.PlayerLoginFinishCsReq).build());
            require(legacy.legacySendRequests.equals(List.of(CmdId.PlayerLoginFinishScRsp, CmdId.GetArchiveDataScRsp))
                    && legacy.sentOpcodes.isEmpty(), "关闭候选时原发送调用不变；无传输时不伪造已发帧");
            System.out.println("LOGIN_FINISH_INITIALIZATION=true NEGATIVE_STATE_CASES=3 MALFORMED_REJECTED=true NONLOCAL_REJECTED=true LEGACY_UNCHANGED=true");
            System.out.println("BOUNDARY=固定参考时序候选；未证明正式客户端需要7503或完成本地接入");
            return passed;
        } finally {
            content.values().clear();
            originalContent.forEach(content::add);
            config.candidate450.enabled = originalEnabled;
            config.logOptions.packets = originalPackets;
            config.logOptions.sessionDiagnostics = originalDiagnostics;
        }
    }

    private static JsonObject decode(String name, byte[] body) throws Exception {
        var message = Release450Candidate.message(name);
        message.mergeFrom(ProtoSource.newInstance(body));
        return Release450Candidate.json(message);
    }

    private static void require(boolean result, String detail) {
        if (!result) throw new AssertionError(detail);
        passed++;
        System.out.println("PASS=" + detail);
    }

    private static void feed(GameSession session, byte[] bytes) {
        var buffer = Unpooled.wrappedBuffer(bytes);
        try { session.onMessage(buffer); } finally { buffer.release(); }
    }

    private static final class RecordingSession extends GameSession {
        final boolean route;
        final GameServerPacketHandler router = new GameServerPacketHandler(false);
        final List<Integer> receivedOpcodes = new ArrayList<>();
        final List<byte[]> receivedBodies = new ArrayList<>();
        final List<Integer> sentOpcodes = new ArrayList<>();
        final List<byte[]> sentBodies = new ArrayList<>();
        final List<Integer> legacySendRequests = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        InetSocketAddress remote = new InetSocketAddress("127.0.0.1", 1);
        boolean closed;
        RecordingSession(boolean route) {
            super(null);
            this.route = route;
            if (route) router.registerPacketHandler(HandlerPlayerLoginFinishCsReq.class);
        }
        @Override public InetSocketAddress getAddress() { return remote; }
        @Override protected void handlePacket(int cmd, byte[] body) {
            if (route) router.handle(this, cmd, body);
            else { receivedOpcodes.add(cmd); receivedBodies.add(body.clone()); }
        }
        @Override public void send(byte[] frame) {
            var sink = new RecordingSession(false);
            feed(sink, frame);
            sentOpcodes.addAll(sink.receivedOpcodes);
            sentBodies.addAll(sink.receivedBodies);
        }
        @Override public void send(int cmd) {
            if (getCandidate450() == null) legacySendRequests.add(cmd);
            super.send(cmd);
        }
        @Override void diagnose(String direction, String event, int cmd, int bytes, String detail) {
            if (event.contains("FAILED") || event.contains("EXCEPTION")) errors.add(event + ":" + detail);
        }
        @Override public void close() { closed = true; }
        void clearSent() { sentOpcodes.clear(); sentBodies.clear(); errors.clear(); }
    }
}
