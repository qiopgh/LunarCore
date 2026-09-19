package emu.lunarcore.server.game;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import emu.lunarcore.LunarCore;
import emu.lunarcore.game.account.Account;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.server.packet.CmdIdUtils;
import emu.lunarcore.server.packet.SessionState;
import emu.lunarcore.util.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import kcp.highway.Ukcp;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import us.hebi.quickbuf.ProtoMessage;

@Getter
public class GameSession {
    private static final AtomicLong NEXT_DIAGNOSTIC_ID = new AtomicLong();
    private final long diagnosticId = NEXT_DIAGNOSTIC_ID.incrementAndGet();
    private final GameServer server;
    private final Int2LongMap packetCooldown;
    private final Release450Candidate candidate450;
    private InetSocketAddress address;

    private Account account;
    private Player player;

    @Setter private boolean sendHello = false;

    // Network
    @Getter(AccessLevel.PRIVATE) private Ukcp ukcp;
    
    // Flags
    private SessionState state = SessionState.WAITING_FOR_TOKEN;
    private boolean useSecretKey;

    // 包内离线回归可直接构造会话，不创建 KCP 监听、游戏循环或数据库。
    GameSession(GameServer server) {
        this.server = server;
        this.packetCooldown = new Int2LongOpenHashMap();
        this.candidate450 = LunarCore.getConfig().getCandidate450().enabled ? new Release450Candidate(this) : null;
    }

    public GameSession(GameServer server, Ukcp ukcp) {
        this(server);
        this.ukcp = ukcp;
        this.address = this.ukcp.user().getRemoteAddress();
    }

    public int getUid() {
        return this.player.getUid();
    }

    public boolean useSecretKey() {
        return useSecretKey;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public void setPlayer(Player player) {
        this.player = player;
        this.player.setSession(this);
        this.getServer().registerPlayer(player);
    }

    public void setUseSecretKey(boolean key) {
        this.useSecretKey = key;
    }

    public void setState(SessionState state) {
        SessionState previous = this.state;
        this.state = state;
        diagnose("STATE", "STATE_CHANGED", -1, 0, previous + "->" + state);
    }

    public void onConnect() {
        if (LunarCore.getConfig().getLogOptions().connections) {
            LunarCore.getLogger().info("Client connected from " + address.getHostString());
        }
    }

    public void onDisconnect() {
        if (LunarCore.getConfig().getLogOptions().connections) {
            LunarCore.getLogger().info("Client disconnected from " + address.getHostString());
        }

        this.setState(SessionState.INACTIVE);

        if (player != null) {
            // Handle player logout event
            player.onLogout();
            
            // Save first
            player.save();
            
            // Deregister player from server
            this.getServer().deregisterPlayer(player);
        }
    }

    public void onMessage(ByteBuf packet) {
        try {
            // Decrypt and turn back into a packet
            // Crypto.xor(packet.array(), useSecretKey() ? Crypto.ENCRYPT_KEY : Crypto.DISPATCH_KEY);

            // Decode
            while (packet.readableBytes() > 0) {
                // Length
                if (packet.readableBytes() < 16) {
                    diagnose("RECV", "TRUNCATED_FRAME", -1, packet.readableBytes(), "MINIMUM_16");
                    return;
                }

                // Packet header sanity check
                int constHeader = packet.readInt();
                if (constHeader != BasePacket.HEADER_CONST) {
                    diagnose("RECV", "BAD_HEADER", -1, packet.readableBytes(), "MAGIC");
                    return; // Bad packet
                }

                // Data
                int opcode = packet.readUnsignedShort();
                int headerLength = packet.readUnsignedShort();
                int dataLength = packet.readInt();

                // 在分配正文前检查长度；长整型计算避免长度相加溢出。
                long required = (long) headerLength + dataLength + Integer.BYTES;
                if (dataLength < 0) {
                    diagnose("RECV", "INVALID_LENGTH", opcode, dataLength, "NEGATIVE_BODY");
                    return;
                }
                if (required > packet.readableBytes()) {
                    diagnose("RECV", "TRUNCATED_BODY", opcode, dataLength, "INCOMPLETE_FRAME");
                    return;
                }

                // Packet tail sanity check
                int constTail = packet.getInt(packet.readerIndex() + headerLength + dataLength);
                if (constTail != BasePacket.TAIL_CONST) {
                    diagnose("RECV", "BAD_TAIL", opcode, dataLength, "MAGIC");
                    return; // Bad packet
                }

                byte[] data = new byte[dataLength];
                packet.skipBytes(headerLength);
                packet.readBytes(data);
                packet.skipBytes(Integer.BYTES);
                diagnose("RECV", "FRAME_ACCEPTED", opcode, dataLength, "headerBytes=" + headerLength);

                // Log packet
                if (LunarCore.getConfig().getLogOptions().packets) {
                    if (!(LunarCore.getConfig().getLogOptions().filterLoopingPackets && CmdIdUtils.IGNORED_LOG_PACKETS.contains(opcode))) {
                        logPacket("RECV", opcode, data);
                    }
                }

                // Handle
                handlePacket(opcode, data);
            }
        } catch (Exception e) {
            diagnose("RECV", "FRAME_EXCEPTION", -1, packet.readableBytes(), e.getClass().getSimpleName());
            if (!isSessionDiagnosticsEnabled()) {
                e.printStackTrace();
            }
        } finally {
            // packet.release();
        }
    }

    // 保留原分发器；包内回归只替换业务接收端，不另写封包或传输实现。
    protected void handlePacket(int opcode, byte[] data) {
        getServer().getPacketHandler().handle(this, opcode, data);
    }

    boolean isSessionDiagnosticsEnabled() {
        return LunarCore.getConfig().getLogOptions().sessionDiagnostics;
    }

    void diagnose(String direction, String event, int opcode, int bytes, String detail) {
        if (isSessionDiagnosticsEnabled()) {
            LunarCore.getLogger().info(
                    "SESSION_DIAG session={} direction={} event={} cmd={} bytes={} state={} detail={}",
                    diagnosticId, direction, event, opcode, bytes, state, detail);
        }
    }

    public void send(BasePacket packet) {
        if (candidate450 != null) {
            try {
                packet = candidate450.outgoing(packet);
                if (packet == null) return;
            } catch (Exception error) {
                diagnose("SEND", "CANDIDATE_ENCODE_FAILED", packet.getCmdId(), 0, error.getClass().getSimpleName());
                return;
            }
        }
        // Test
        if (packet.getCmdId() <= 0) {
            diagnose("SEND", "MISSING_CMDID", packet.getCmdId(), 0, packet.getClass().getSimpleName());
            if (LunarCore.getConfig().getLogOptions().packets) {
                LunarCore.getLogger().warn("Tried to send packet with missing cmd id!");
            }
            return;
        }

        // Send
        this.send(packet.build());

        // Log
        if (LunarCore.getConfig().getLogOptions().packets) {
            if (!(LunarCore.getConfig().getLogOptions().filterLoopingPackets && CmdIdUtils.IGNORED_LOG_PACKETS.contains(packet.getCmdId()))) {
                logPacket("SEND", packet.getCmdId(), packet.getData());
            }
        }
    }
    
    /**
     * Sends a cached packet with the specified cmd id. If the packet isnt cacheable, then an empty packet is sent.
     * @param cmdId
     */
    public void send(int cmdId) {
        if (candidate450 != null) {
            try {
                BasePacket packet = candidate450.empty(cmdId);
                if (packet != null) send(packet);
            } catch (Exception error) {
                diagnose("SEND", "CANDIDATE_ENCODE_FAILED", cmdId, 0, error.getClass().getSimpleName());
            }
            return;
        }
        // Test
        if (cmdId <= 0) {
            diagnose("SEND", "MISSING_CMDID", cmdId, 0, "CACHED_PACKET");
            if (LunarCore.getConfig().getLogOptions().packets) {
                LunarCore.getLogger().warn("Tried to send packet with missing cmd id!");
            }
            return;
        }
        
        // Get packet from the server's packet cache. This will allow us to reuse empty packets if needed.
        if (this.ukcp != null) {
            this.ukcp.write(getServer().getPacketCache().getCachedPacket(cmdId));
            diagnose("SEND", "CACHED_WRITE_QUEUED", cmdId, 0, "KCP");
        } else {
            diagnose("SEND", "NO_TRANSPORT", cmdId, 0, "CACHED_PACKET");
        }
        
        // Log
        if (LunarCore.getConfig().getLogOptions().packets) {
            if (!(LunarCore.getConfig().getLogOptions().filterLoopingPackets && CmdIdUtils.IGNORED_LOG_PACKETS.contains(cmdId))) {
                logPacket("SEND", cmdId, Utils.EMPTY_BYTE_ARRAY);
            }
        }
    }

    public void send(byte[] bytes) {
        if (this.ukcp != null) {
            ByteBuf buf = Unpooled.wrappedBuffer(bytes);
            try {
                this.ukcp.write(buf);
                diagnose("SEND", "WRITE_QUEUED", -1, bytes.length, "KCP_FRAME");
            } finally {
                buf.release();
            }
        } else {
            diagnose("SEND", "NO_TRANSPORT", -1, bytes.length, "KCP_FRAME");
        }
    }
    
    public void logPacket(String sendOrRecv, int opcode, ProtoMessage<?> payload) {
        logPacket(sendOrRecv, opcode, payload != null ? payload.toByteArray() : Utils.EMPTY_BYTE_ARRAY);
    }

    public void logPacket(String sendOrRecv, int opcode, byte[] payload) {
        LunarCore.getLogger().info(sendOrRecv + ": " + CmdIdUtils.getCmdIdName(opcode) + " (" + opcode + ")" + System.lineSeparator() + Utils.bytesToHex(payload));
    }

    public void close() {
        if (this.ukcp != null) {
            this.ukcp.close();
        }
    }
}
