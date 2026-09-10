package emu.lunarcore;

import emu.lunarcore.proto.PlayerGetTokenCsReqOuterClass.PlayerGetTokenCsReq;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdId;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * S0 离线回归：直接调用现有 Quickbuf 生成类和 BasePacket，不启动业务服务。
 * 样本来自旧版字段布局的人工合成，不是正式版 4.5.0 的客户端抓包。
 */
public final class S0BaselineRegression {
    private static final HexFormat HEX = HexFormat.of();
    private static final String UID = "FIXTURE_UID";
    private static final String TOKEN = "SYNTHETIC_TOKEN";
    private static final String FIELD_ORDER_HEX =
            "120b464958545552455f5549443a0f53594e5448455449435f544f4b454e5003";
    private static final String GENERATED_ORDER_HEX =
            "5003120b464958545552455f5549443a0f53594e5448455449435f544f4b454e";
    private static int passed;

    private S0BaselineRegression() {}

    public static void main(String[] args) throws IOException {
        boolean negativeControl = args.length == 1 && "--negative-control".equals(args[0]);
        require(args.length == 0 || negativeControl, "仅允许 --negative-control 参数");

        PlayerGetTokenCsReq decoded = PlayerGetTokenCsReq.parseFrom(HEX.parseHex(FIELD_ORDER_HEX));
        requireFields(decoded);
        pass("golden_decode_fields_2_7_10");

        PlayerGetTokenCsReq generated = PlayerGetTokenCsReq.newInstance()
                .setAccountUid(UID).setToken(TOKEN).setPlatformType(3);
        byte[] generatedBytes = generated.toByteArray();
        require(GENERATED_ORDER_HEX.equals(HEX.formatHex(generatedBytes)), "旧生成器输出顺序发生变化");
        requireFields(PlayerGetTokenCsReq.parseFrom(generatedBytes));
        require(decoded.equals(generated), "合法字段顺序变化后语义不一致");
        pass("golden_encode_roundtrip_and_order");

        // 记录旧生成器的丢弃行为，不把它表述为新协议兼容或未知字段保留能力。
        PlayerGetTokenCsReq unknown = PlayerGetTokenCsReq.parseFrom(
                HEX.parseHex(FIELD_ORDER_HEX + "f80701"));
        requireFields(unknown);
        require(Arrays.equals(generatedBytes, unknown.toByteArray()), "未知字段处理与旧基线不符");
        pass("unknown_field_127_discarded_baseline");

        expectMalformed("120b4142", "truncated_string_rejected");
        expectMalformed("5080", "truncated_varint_rejected");

        require(CmdId.PlayerGetTokenCsReq == 14, "旧版 token CmdId 已改变");
        BasePacket packet = new BasePacket(CmdId.PlayerGetTokenCsReq);
        packet.setData(generated);
        byte[] frame = packet.build();
        requireFrame(frame, 14, generatedBytes);
        String expected = "9d74c714000e000000000020" + GENERATED_ORDER_HEX + "d7a152c8";
        require(expected.equals(HEX.formatHex(frame)), "旧版封包黄金字节不一致");
        pass("basepacket_generated_golden_frame");

        byte[] rawBody = HEX.parseHex(FIELD_ORDER_HEX);
        packet.setData(rawBody);
        requireFrame(packet.build(), 14, rawBody);
        pass("basepacket_raw_body");

        BasePacket empty = new BasePacket(65535);
        requireFrame(empty.build(), 65535, new byte[0]);
        pass("basepacket_empty_and_unsigned_cmdid");

        // 当前发包器未检查越界值，只写低 16 位；在 S0 固定行为而不修业务实现。
        empty.setCmdId(65536);
        requireFrame(empty.build(), 0, new byte[0]);
        empty.setCmdId(-1);
        requireFrame(empty.build(), 65535, new byte[0]);
        pass("cmdid_out_of_range_truncates_baseline");

        generated.clear();
        require(!generated.hasAccountUid() && !generated.hasToken() && !generated.hasPlatformType(),
                "clear 未清除字段存在性");
        require(generated.toByteArray().length == 0, "clear 后并非空消息");
        pass("clear_resets_presence");

        System.out.println("FIXTURE_HEX=" + FIELD_ORDER_HEX);
        System.out.println("FRAME_HEX=" + HEX.formatHex(frame));
        if (negativeControl) {
            System.out.println("NEGATIVE_CONTROL=故意翻转黄金帧 magic，必须失败");
            frame[0] ^= 1;
            requireFrame(frame, 14, generatedBytes);
            throw new AssertionError("负面控制未被检测");
        }
        System.out.println("S0_BASELINE_TESTS_PASSED=" + passed);
        System.out.println("BOUNDARY=旧版合成离线回归；不证明 4.5.0 接入；未启动网络或数据库");
    }

    private static void requireFields(PlayerGetTokenCsReq message) {
        require(message.hasAccountUid() && UID.contentEquals(message.getAccountUid()), "account_uid 不符");
        require(message.hasToken() && TOKEN.contentEquals(message.getToken()), "token 不符");
        require(message.hasPlatformType() && message.getPlatformType() == 3, "platform_type 不符");
    }

    private static void expectMalformed(String hex, String label) {
        try {
            PlayerGetTokenCsReq.parseFrom(HEX.parseHex(hex));
        } catch (IOException expected) {
            pass(label);
            return;
        }
        throw new AssertionError("截断 Protobuf 未被拒绝：" + label);
    }

    // 此处是测试侧的发包断言，不是新增的生产接收解析器。
    private static void requireFrame(byte[] frame, int cmdId, byte[] body) {
        require(frame.length == 16 + body.length, "frame 长度不符");
        ByteBuffer bytes = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        require(bytes.getInt() == 0x9d74c714, "frame magic 不符");
        require(Short.toUnsignedInt(bytes.getShort()) == cmdId, "CmdId 不符");
        require(Short.toUnsignedInt(bytes.getShort()) == 0, "headerLength 不符");
        require(bytes.getInt() == body.length, "bodyLength 不符");
        byte[] actual = new byte[body.length];
        bytes.get(actual);
        require(Arrays.equals(body, actual), "body 内容不符");
        require(bytes.getInt() == 0xd7a152c8 && !bytes.hasRemaining(), "frame tail 不符");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static void pass(String label) {
        passed++;
        System.out.println("PASS=" + label);
    }
}
