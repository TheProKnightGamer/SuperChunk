package dev.superchunk.com.ishland.c2me.server.utils.common;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.fml.loading.FMLLoader;

public class C2MECommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("c2me")
                        .requires(source -> source.hasPermission(2))
                        // The "notick" subcommand depends on the c2me-notickvd module
                        // (dev.superchunk.com.ishland.c2me.notickvd.*), which is not yet merged into SuperChunk.
                        // Re-enable this block once notickvd is ported.
//                        .then(
//                                Commands.literal("notick")
//                                        .requires(unused -> dev.superchunk.com.ishland.c2me.notickvd.ModuleEntryPoint.enabled)
//                                        .executes(C2MECommands::noTickCommand)
//                        )
                        .then(
                                Commands.literal("debug")
                                        .requires(unused -> !FMLLoader.isProduction())
//                                        .then(
//                                                Commands.literal("mobcaps")
//                                                        .requires(unused -> dev.superchunk.com.ishland.c2me.notickvd.ModuleEntryPoint.enabled)
//                                                        .executes(C2MECommands::mobcapsCommand)
//                                        )
                        )
        );
    }

//    private static int noTickCommand(CommandContext<CommandSourceStack> ctx) {
//        final ServerChunkCache chunkManager = ctx.getSource().getLevel().getChunkSource();
//        final DistanceManager ticketManager = ((IServerChunkManager) chunkManager).getTicketManager();
//        final long noTickPendingTicketUpdates = ((ChunkTicketManagerExtension) ticketManager).c2me$getPendingLoadsCount();
//        ctx.getSource().sendSuccess(() -> Component.nullToEmpty(String.format("No-tick chunk pending chunk loads: %d", noTickPendingTicketUpdates)), true);
//
//        return 0;
//    }

}
