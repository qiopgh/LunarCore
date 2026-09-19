package emu.lunarcore.server.http.handlers;

import org.jetbrains.annotations.NotNull;

import emu.lunarcore.LunarCore;
import emu.lunarcore.server.http.HttpServer;
import emu.lunarcore.proto.DispatchRegionDataOuterClass.DispatchRegionData;
import emu.lunarcore.server.game.Release450Candidate;
import emu.lunarcore.util.Utils;
import java.util.Base64;

import io.javalin.http.Context;
import io.javalin.http.Handler;

public class QueryDispatchHandler implements Handler {
    private final HttpServer server;
    
    public QueryDispatchHandler(HttpServer server) {
        this.server = server;
    }

    @Override
    public void handle(@NotNull Context ctx) throws Exception {
        // Log
        if (LunarCore.getConfig().getLogOptions().connections) {
            LunarCore.getLogger().info("Client request: query_dispatch");
        }
        
        // Send region list to client
        String regions = server.getRegionList();
        if (LunarCore.getConfig().getCandidate450().enabled) {
            var legacy = DispatchRegionData.parseFrom(Base64.getDecoder().decode(regions));
            regions = Utils.base64Encode(Release450Candidate.translate("Dispatch", legacy).toByteArray());
        }
        ctx.result(regions);
    }

}
