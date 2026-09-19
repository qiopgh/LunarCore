package emu.lunarcore.server.packet.recv;

import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.game.Release450Candidate;
import emu.lunarcore.server.packet.CmdId;
import emu.lunarcore.server.packet.Opcodes;
import emu.lunarcore.server.packet.PacketHandler;
import emu.lunarcore.server.packet.send.PacketContentPackageGetDataScRsp;

@Opcodes(CmdId.PlayerLoginFinishCsReq)
public class HandlerPlayerLoginFinishCsReq extends PacketHandler {

    @Override
    public void handle(GameSession session, byte[] data) throws Exception {
        if (session.getCandidate450() != null) {
            // 沿用固定参考的候选时序和原内容资源构造器；不是正式客户端时序已验证。
            session.send(Release450Candidate.packet("ContentPackageSyncDataScNotify",
                    Release450Candidate.json(new PacketContentPackageGetDataScRsp().getData())));
        }
        session.send(CmdId.PlayerLoginFinishScRsp);
        session.send(CmdId.GetArchiveDataScRsp);
    }

}
