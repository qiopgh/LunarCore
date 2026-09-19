package emu.lunarcore.server.game;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mongodb.client.MongoClient;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.database.DatabaseManager;
import emu.lunarcore.game.account.Account;
import emu.lunarcore.server.http.HttpServer;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.server.packet.recv.*;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Timer;
import us.hebi.quickbuf.ProtoSource;

/** 外部固定资源、独立内存数据库、原业务处理器的合成链；不启动游戏客户端或 KCP 监听。 */
public final class Candidate450ResourceSmoke {
    private static int passed;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要已有资源目录参数");
        Config config = LunarCore.getConfig();
        config.resourceDir = Path.of(args[0]).toAbsolutePath().toString();
        config.dataDir = Path.of("data").toAbsolutePath().toString();
        config.httpServer.bindAddress = "127.0.0.1";
        config.httpServer.publicAddress = "127.0.0.1";
        config.httpServer.bindPort = 0;
        config.httpServer.useSSL = false;
        config.gameServer.bindAddress = "127.0.0.1";
        config.gameServer.publicAddress = "127.0.0.1";
        config.logOptions.connections = false;
        config.logOptions.packets = false;
        config.logOptions.sessionDiagnostics = true;
        config.candidate450.enabled = true;
        set("hotfixData", new emu.lunarcore.HotfixData());
        ResourceLoader.loadAll();
        java.nio.file.Files.writeString(Path.of(config.dataDir, "Banners.json"), "[]\n");
        require(GameData.getAvatarExcelMap().get(8001) != null, "固定角色资源 8001");
        require(GameData.getFloorInfo(20001, 20001001) != null, "原初始场景资源");
        var cocoon = GameData.getCocoonExcelMap().get(1201, 0);
        require(cocoon != null && GameData.getStageExcelMap().get(1043010) != null, "固定茧 1201 与阶段 1043010");
        MongoServer mongo = new MongoServer(new MemoryBackend());
        DatabaseManager database = null;
        HttpServer http = null;
        GameServer server = null;
        RecordingSession session = null;
        try {
            mongo.bind(new InetSocketAddress("127.0.0.1", 0));
            Config.DatabaseInfo info = new Config.DatabaseInfo();
            info.uri = mongo.getConnectionString();
            info.collection = "candidate450_synthetic_only";
            info.useInternal = false;
            database = new DatabaseManager(info, LunarCore.ServerType.BOTH);
            set("accountDatabase", database);
            set("gameDatabase", database);
            Account account = new Account("candidate450_fixture");
            account.save();
            config.candidate450.localAccountUid = account.getUid();
            config.validate();
            http = new HttpServer(LunarCore.ServerType.BOTH);
            set("httpServer", http);
            server = new GameServer(config.gameServer);
            set("gameServer", server);
            cancelTimer(server);
            // 显式覆盖测试类路径里的同编号假处理器，确保本测试运行生产业务。
            server.getPacketHandler().registerPacketHandler(HandlerPlayerGetTokenCsReq.class);
            server.getPacketHandler().registerPacketHandler(HandlerPlayerLoginCsReq.class);
            server.getPacketHandler().registerPacketHandler(HandlerStartCocoonStageCsReq.class);
            RegionInfo region = new RegionInfo(server);
            region.setUp(true);
            region.save();
            http.start();
            try (HttpClient client = HttpClient.newHttpClient()) {
                JsonObject dispatch = http(client, http, "/query_dispatch", "Dispatch");
                require(dispatch.getAsJsonArray("regionList").size() == 1, "原 dispatch 真实回环 HTTP 响应");
                JsonObject gateway = http(client, http, "/query_gateway", "GateServer");
                require(gateway.get("ip").getAsString().equals("127.0.0.1") && !gateway.get("useTcp").getAsBoolean(), "原 gateway 真实回环 HTTP 响应");
            }
            session = new RecordingSession(server);
            login(session);
            int uid = session.getPlayer().getUid();
            require(session.getPlayer().getScene() != null, "原登录处理器创建场景");
            JsonObject avatars = session.request("GetAvatarDataCsReq", "{}", "GetAvatarDataScRsp");
            require(avatars.getAsJsonArray("avatarList").size() > 0, "原角色处理器与候选正文");
            JsonObject lineup = session.request("GetCurLineupDataCsReq", "{}", "GetCurLineupDataScRsp");
            require(lineup.getAsJsonObject("lineup").getAsJsonArray("avatarList").get(0).getAsJsonObject().get("id").getAsInt() == 8001, "固定单角色编队 8001");
            require(session.request("GetCurSceneInfoCsReq", "{}", "GetCurSceneInfoScRsp").has("scene"), "原场景处理器与候选正文");
            int stamina = session.getPlayer().getStamina();
            int hp = session.getPlayer().getCurrentLeaderAvatar().getCurrentHp(session.getPlayer().getCurrentLineup());
            JsonObject start = session.request("StartCocoonStageCsReq", "{\"cocoonId\":1201,\"worldLevel\":0,\"wave\":1}", "StartCocoonStageScRsp");
            require(start.has("battleInfo"), "原 BattleService 开战成功");
            JsonObject battle = start.getAsJsonObject("battleInfo");
            int battleId = battle.get("battleId").getAsInt();
            require(battle.get("stageId").getAsInt() == 1043010 && battle.getAsJsonArray("battleAvatarList").size() == 1, "固定角色与真实阶段进入战斗正文");
            require(battle.getAsJsonArray("monsterWaveList").size() > 0, "真实怪物波次非空");
            JsonObject query = session.request("GetCurBattleInfoCsReq", "{}", "GetCurBattleInfoScRsp");
            require(battle.equals(query.getAsJsonObject("battleInfo")), "查询当前战斗复用相同种子与正文");
            String wrong = "{\"battleId\":" + (battleId + 1) + ",\"stageId\":1043010,\"endStatus\":1}";
            require(session.request("PVEBattleResultCsReq", wrong, "PVEBattleResultScRsp").get("retcode").getAsInt() != 0, "错误战斗身份被拒绝");
            require(session.getPlayer().getStamina() == stamina && session.getPlayer().getBattle() != null, "错误结果不扣体力不清战斗");
            String win = "{\"battleId\":" + battleId + ",\"stageId\":1043010,\"endStatus\":1,\"stt\":{\"roundCnt\":1}}";
            JsonObject result = session.request("PVEBattleResultCsReq", win, "PVEBattleResultScRsp");
            require(!result.has("retcode") || result.get("retcode").getAsInt() == 0, "原结算处理器返回成功");
            require(session.getPlayer().getBattle() == null && session.getPlayer().getStamina() == stamina - 40, "结算清除战斗且仅扣固定 40 体力");
            require(session.getPlayer().getCurrentLeaderAvatar().getCurrentHp(session.getPlayer().getCurrentLineup()) == hp, "未知结算 HP 映射不覆盖已有角色状态");
            require(session.request("PVEBattleResultCsReq", win, "PVEBattleResultScRsp").get("retcode").getAsInt() != 0 && session.getPlayer().getStamina() == stamina - 40, "重复结算拒绝且不重复扣体力");
            query = session.request("GetCurBattleInfoCsReq", "{}", "GetCurBattleInfoScRsp");
            require(!query.has("battleInfo") && query.get("lastEndStatus").getAsInt() == 1, "结算后当前战斗为空");
            require(session.request("GetCurSceneInfoCsReq", "{}", "GetCurSceneInfoScRsp").has("scene"), "结算后仍可查询原场景");
            session.onDisconnect();
            require(server.getPlayerCount() == 0, "原断开路径保存并注销玩家");
            session = new RecordingSession(server);
            login(session);
            require(session.getPlayer().getUid() == uid && session.getPlayer().getStamina() == stamina - 40, "同账号重新登录读取原持久化玩家与体力");
            require(session.getPlayer().getBattle() == null && session.getPlayer().getCurrentLeaderAvatar() != null, "重新登录无残留战斗且角色可读取");
            System.out.println("CANDIDATE450_RESOURCE_TESTS_PASSED=" + passed);
            System.out.println("BOUNDARY=真实资源与原业务的本地合成链；未运行官方客户端，未验证传输、正式认证、真实战斗计算或结算 HP/SP 字段");
        } finally {
            if (session != null && session.getPlayer() != null && session.getState() != SessionState.INACTIVE) session.onDisconnect();
            if (server != null) {
                cancelTimer(server);
                // 原监视器关闭时会记录 ClosedWatchServiceException；这是测试主动清理的预期日志。
                server.getGachaService().getWatchService().close();
                server.getGachaService().getWatchThread().join(5000);
                if (server.getGachaService().getWatchThread().isAlive()) throw new AssertionError("目录监视线程未退出");
            }
            if (http != null) http.getApp().stop();
            if (database != null) {
                // 固定 Morphia 版本未公开关闭方法；测试只关闭自身创建的客户端。
                var field = database.getDatastore().getClass().getDeclaredField("mongoClient");
                field.setAccessible(true);
                ((MongoClient) field.get(database.getDatastore())).close();
            }
            mongo.shutdownNow();
            System.out.println("CLEANUP=测试 HTTP、数据库及计时器已关闭");
        }
    }

    private static void login(RecordingSession session) throws Exception {
        session.request("PlayerGetTokenCsReq", "{\"platform\":2}", "PlayerGetTokenScRsp");
        require(session.getState() == SessionState.WAITING_FOR_LOGIN && session.getPlayer() != null, "原 token 处理器加载独立测试账号");
        JsonObject login = session.request("PlayerLoginCsReq", "{\"loginRandom\":1234567891234}", "PlayerLoginScRsp");
        require(session.getState() == SessionState.ACTIVE && login.get("loginRandom").getAsLong() == 1234567891234L, "原 login 处理器推进 ACTIVE 并回显随机值");
    }
    private static JsonObject http(HttpClient client, HttpServer http, String route, String type) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + http.getApp().port() + route);
        var response = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new AssertionError("回环 HTTP 状态：" + response.statusCode());
        var message = Release450Candidate.message(type);
        message.mergeFrom(ProtoSource.newInstance(Base64.getDecoder().decode(response.body())));
        return Release450Candidate.json(message);
    }
    private static void set(String name, Object value) throws Exception {
        var field = LunarCore.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    private static void cancelTimer(GameServer server) throws Exception {
        var field = GameServer.class.getDeclaredField("gameLoopTimer"); field.setAccessible(true); ((Timer) field.get(server)).cancel();
    }
    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
        passed++; System.out.println("PASS=" + detail);
    }
    private static final class RecordingSession extends GameSession {
        final List<byte[]> sent = new ArrayList<>();
        RecordingSession(GameServer server) { super(server); }
        @Override public InetSocketAddress getAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        @Override public void send(byte[] frame) { sent.add(frame.clone()); }
        JsonObject request(String type, String body, String response) throws Exception {
            sent.clear();
            byte[] frame = Release450Candidate.packet(type, JsonParser.parseString(body).getAsJsonObject()).build();
            var buffer = Unpooled.wrappedBuffer(frame);
            try { onMessage(buffer); } finally { buffer.release(); }
            for (byte[] raw : sent) {
                ByteBuffer out = ByteBuffer.wrap(raw);
                if (out.getInt() != BasePacket.HEADER_CONST) throw new AssertionError("响应包头");
                int cmd = Short.toUnsignedInt(out.getShort());
                int head = Short.toUnsignedInt(out.getShort());
                int length = out.getInt();
                out.position(12 + head);
                byte[] payload = new byte[length]; out.get(payload);
                if (out.getInt() != BasePacket.TAIL_CONST || out.hasRemaining()) throw new AssertionError("响应包尾");
                if (cmd == Release450Candidate.command(response)) {
                    var message = Release450Candidate.message(response);
                    message.mergeFrom(ProtoSource.newInstance(payload));
                    return Release450Candidate.json(message);
                }
            }
            throw new AssertionError("没有收到 " + response);
        }
    }
}
