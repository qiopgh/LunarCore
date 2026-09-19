package emu.lunarcore;

import emu.lunarcore.proto.StartCocoonStageScRspOuterClass.StartCocoonStageScRsp;
import emu.lunarcore.proto.v450.StartCocoonStageErrorOuterClass.StartCocoonStageScRspError;
import emu.lunarcore.server.packet.send.PacketStartCocoonStageScRsp;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import us.hebi.quickbuf.ProtoSource;

/** 复用真实构造器、生成类和 BasePacket；只覆盖无战斗对象的错误响应。 */
public final class Release450CocoonErrorRegression {
    private static final HexFormat HEX = HexFormat.of();
    private static int passed;

    private Release450CocoonErrorRegression() {}

    public static void main(String[] args) throws Exception {
        boolean negative = args.length == 1 && "--negative-control".equals(args[0]);
        require(args.length == 0 || negative, "仅允许 --negative-control 参数");

        var packet = new PacketStartCocoonStageScRsp(null);
        require(packet.getCmdId() == 1479, "茧响应编号不是 1479");
        pass("cmdid_1479");
        require(packet.getData() instanceof StartCocoonStageScRspError,
                "错误分支没有使用独立版本化生成类");
        pass("versioned_error_type");

        byte[] body = packet.getData().toByteArray();
        require("6801".equals(HEX.formatHex(body)), "错误响应正文不是字段 13、值 1");
        var source = ProtoSource.newInstance(body);
        require(source.readTag() == 104 && source.readUInt32() == 1 && source.isAtEnd(),
                "Quickbuf 未能精确读取字段 13、值 1");
        pass("field_13_value_1");

        var decoded = StartCocoonStageScRspError.parseFrom(body);
        require(decoded.getRetcode() == 1 && "6801".equals(HEX.formatHex(decoded.toByteArray())),
                "版本化生成类往返不一致");
        pass("generated_roundtrip");
        require(StartCocoonStageScRspError.parseFrom(HEX.parseHex("2801")).getRetcode() == 0,
                "旧字段 5 不应被读取为新错误码");
        require(StartCocoonStageScRspError.parseFrom(HEX.parseHex("6800")).getRetcode() != 1,
                "错误值负例未能区分");
        pass("wrong_field_and_value_controls");

        try {
            StartCocoonStageScRspError.parseFrom(HEX.parseHex("6880"));
            throw new AssertionError("截断 varint 未被拒绝");
        } catch (IOException expected) {
            pass("truncated_varint_rejected");
        }

        // 原生成类必须保持旧线格式，不能用全局替换破坏未迁移的定义。
        byte[] legacy = StartCocoonStageScRsp.newInstance().setRetcode(1).toByteArray();
        require("2801".equals(HEX.formatHex(legacy)), "旧版生成类发生了非预期变化");
        pass("legacy_generated_type_unchanged");

        byte[] frame = packet.build();
        checkFrame(frame);
        require("9d74c71405c70000000000026801d7a152c8".equals(HEX.formatHex(frame)),
                "真实 BasePacket 输出与黄金帧不符");
        pass("basepacket_golden_frame");
        System.out.println("BODY_HEX=" + HEX.formatHex(body));
        System.out.println("FRAME_HEX=" + HEX.formatHex(frame));
        if (negative) {
            System.out.println("NEGATIVE_CONTROL=故意翻转首魔数，测试必须失败");
            frame[0] ^= 1;
            checkFrame(frame);
            throw new AssertionError("负面控制未被检测");
        }
        System.out.println("RELEASE450_COCOON_ERROR_TESTS_PASSED=" + passed);
        System.out.println("BOUNDARY=仅错误响应字段投影；未启动客户端或服务；成功战斗正文未验证");
    }

    private static void checkFrame(byte[] frame) {
        ByteBuffer data = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        require(frame.length == 18, "帧总长度不符");
        require(data.getInt() == 0x9d74c714, "首魔数不符");
        require(Short.toUnsignedInt(data.getShort()) == 1479, "帧编号不符");
        require(data.getShort() == 0 && data.getInt() == 2, "帧长度字段不符");
        require(data.get() == 0x68 && data.get() == 1, "帧中正文不符");
        require(data.getInt() == 0xd7a152c8 && !data.hasRemaining(), "尾魔数或尾部长度不符");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void pass(String name) {
        passed++;
        System.out.println("PASS=" + name);
    }
}
