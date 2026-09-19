package emu.lunarcore.server.packet.send;

import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.proto.StartCocoonStageScRspOuterClass.StartCocoonStageScRsp;
import emu.lunarcore.proto.v450.StartCocoonStageErrorOuterClass.StartCocoonStageScRspError;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;

public class PacketStartCocoonStageScRsp extends BasePacket {

    public PacketStartCocoonStageScRsp(Battle battle) {
        super(CmdId.StartCocoonStageScRsp);

        if (battle == null) {
            // 只适配已复现的错误分支；retcode 的值仍为 1，字段号由 schema 定义。
            this.setData(StartCocoonStageScRspError.newInstance().setRetcode(1));
            return;
        }

        // 成功分支保留旧实现，不作为当前正式构建已兼容的证明。
        var data = StartCocoonStageScRsp.newInstance();

        data.setBattleInfo(battle.toProto())
            .setCocoonId(battle.getMappingInfoId())
            .setWave(battle.getCocoonWave());

        this.setData(data);
    }
}
