package tannyjung.tanshugetrees_mcreator.command;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.RegisterCommandsEvent;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import tannyjung.tanshugetrees.server.Cache;

@Mod.EventBusSubscriber
public class COMMANDCacheStatsCommand {
    @SubscribeEvent
    public static void registerCommand(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("TANSHUGETREES")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("cache")
                    .then(Commands.literal("stats").executes(arguments -> {
                        String detectionStats = Cache.getDetectionCacheStats();
                        String placementStats = Cache.getPlacementIndexStats();
                        String structureStats = Cache.getStructureCacheStats();
                        arguments.getSource().sendSuccess(() -> Component.literal("[THT] " + detectionStats), false);
                        arguments.getSource().sendSuccess(() -> Component.literal("[THT] " + placementStats), false);
                        arguments.getSource().sendSuccess(() -> Component.literal("[THT] " + structureStats), false);
                        return 0;
                    }))
                    .then(Commands.literal("reset").executes(arguments -> {
                        Cache.resetDetectionCacheStats();
                        Cache.resetPlacementIndexStats();
                        Cache.resetStructureCacheStats();
                        arguments.getSource().sendSuccess(() -> Component.literal("[THT] Cache statistics reset"), false);
                        return 0;
                    }))
                )
        );
    }
}
