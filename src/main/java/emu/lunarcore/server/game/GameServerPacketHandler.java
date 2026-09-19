package emu.lunarcore.server.game;

import java.util.Set;

import org.reflections.Reflections;

import emu.lunarcore.LunarCore;
import emu.lunarcore.server.packet.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

@SuppressWarnings("unchecked")
public class GameServerPacketHandler {
    private final Int2ObjectMap<PacketHandler> handlers;

    public GameServerPacketHandler() {
        this(true);
    }

    // 正常入口仍扫描原 handler；离线回归可只登记合成业务处理器。
    GameServerPacketHandler(boolean register) {
        this.handlers = new Int2ObjectOpenHashMap<>();
        if (register) {
            this.registerHandlers();
        }
    }

    public void registerPacketHandler(Class<? extends PacketHandler> handlerClass) {
        try {
            Opcodes opcode = handlerClass.getAnnotation(Opcodes.class);

            if (opcode == null || opcode.disabled() || opcode.value() <= 0) {
                return;
            }
            
            this.handlers.put(opcode.value(), handlerClass.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void registerHandlers() {
        Reflections reflections = new Reflections(LunarCore.class.getPackageName());
        Set<?> handlerClasses = reflections.getSubTypesOf(PacketHandler.class);

        for (Object obj : handlerClasses) {
            this.registerPacketHandler((Class<? extends PacketHandler>) obj);
        }

        LunarCore.getLogger().info("Game Server registered " + this.handlers.size() + " packet handlers");
    }

    public void handle(GameSession session, int cmdId, byte[] data) {
        if (session.getCandidate450() != null) {
            try {
                session.getCandidate450().receive(cmdId, data, (legacyId, legacyBody) -> handleLegacy(session, legacyId, legacyBody));
            } catch (Exception error) {
                session.diagnose("RECV", "CANDIDATE_DECODE_FAILED", cmdId, data.length, error.getClass().getSimpleName());
            }
            return;
        }
        handleLegacy(session, cmdId, data);
    }

    void handleLegacy(GameSession session, int cmdId, byte[] data) {
        PacketHandler handler = this.handlers.get(cmdId);

        if (handler == null) {
            session.diagnose("RECV", "NO_HANDLER", cmdId, data.length, "UNMAPPED");
            return;
        }
        String rejection = rejectionReason(session.getState(), cmdId);
        if (rejection != null) {
            session.diagnose("RECV", "STATE_REJECTED", cmdId, data.length, rejection);
            return;
        }
        try {
            session.diagnose("RECV", "HANDLER_ENTER", cmdId, data.length, handler.getClass().getSimpleName());
            handler.handle(session, data);
            session.diagnose("RECV", "HANDLER_OK", cmdId, data.length, handler.getClass().getSimpleName());
        } catch (Exception ex) {
            // 诊断模式只输出异常类型，避免异常消息带出账号或正文。
            session.diagnose("RECV", "HANDLER_EXCEPTION", cmdId, data.length, ex.getClass().getSimpleName());
            if (!session.isSessionDiagnosticsEnabled()) {
                ex.printStackTrace();
            }
        }
    }

    // 只提取既有状态条件，心跳、token、login 和普通请求的放行语义不变。
    static String rejectionReason(SessionState state, int cmdId) {
        if (cmdId == CmdId.PlayerHeartBeatCsReq) return null;
        if (cmdId == CmdId.PlayerGetTokenCsReq) {
            return state == SessionState.WAITING_FOR_TOKEN ? null : "EXPECTED_WAITING_FOR_TOKEN";
        }
        if (cmdId == CmdId.PlayerLoginCsReq) {
            return state == SessionState.WAITING_FOR_LOGIN ? null : "EXPECTED_WAITING_FOR_LOGIN";
        }
        return state == SessionState.ACTIVE ? null : "EXPECTED_ACTIVE";
    }
}
