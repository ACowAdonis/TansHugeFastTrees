package tannyjung.tanshugetrees_handcode;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import tannyjung.core.game.GameUtils;
import tannyjung.tanshugetrees.TanshugetreesMod;
import tannyjung.tanshugetrees_handcode.config.CustomPackIncompatible;
import tannyjung.tanshugetrees_handcode.config.PackCheckUpdate;
import tannyjung.tanshugetrees_handcode.config.ConfigMain;
import tannyjung.tanshugetrees_handcode.systems.Cache;
import tannyjung.tanshugetrees_handcode.systems.Loop;
import tannyjung.tanshugetrees_handcode.systems.world_gen.FeatureAreaDirt;
import tannyjung.tanshugetrees_handcode.systems.world_gen.FeatureAreaGrass;
import tannyjung.tanshugetrees_handcode.systems.living_tree_mechanics.SeasonDetector;
import tannyjung.tanshugetrees_handcode.systems.world_gen.WorldGenFull;
import tannyjung.tanshugetrees_handcode.systems.world_gen.WorldGenBeforePlants;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber
public class Handcode {

	// ----------------------------------------------------------------------------------------------------

	public static int data_structure_version = 20251023;
	public static String tanny_pack_version = "Alpha";

	public static boolean version_1192 = false;

	// ----------------------------------------------------------------------------------------------------

	public static String path_config = GameUtils.path_game + "/config/tanshugetrees";
	public static String path_world_data = GameUtils.path_game + "/saves/tanshugetrees-error";
	public static String tanny_pack_version_name = ""; // Make this because version can swap to "WIP" by config

    public static boolean thread_pause = false;
    public static ExecutorService thread_main = Executors.newFixedThreadPool(1);
    private static int thread_number = 0;

    // ----------------------------------------------------------------------------------------------------

	public Handcode () {}

	public static void startGame () {

        TanshugetreesMod.LOGGER.info("Starting...");

        // Thread Start
        {

            thread_number = 1;

            thread_main = Executors.newFixedThreadPool(1, name -> {
                Thread thread = new Thread(name);
                thread.setName("Tan's Huge Trees - Main (" + thread_number + "/" + 1 + ")");
                return thread;
            });

        }

        // Registries
        {

            IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();

            DeferredRegister<Feature<?>> REGISTRY = DeferredRegister.create(Registries.FEATURE, TanshugetreesMod.MODID);
            REGISTRY.register("world_gen_before_plants", WorldGenBeforePlants::new);
            REGISTRY.register("area_grass", FeatureAreaGrass::new);
            REGISTRY.register("area_dirt", FeatureAreaDirt::new);

            REGISTRY.register(bus);

        }

        restart(null, false, false);

	}

    public static void restart (LevelAccessor level_accessor, boolean only_world_system, boolean message) {

        thread_pause = true;

        if (only_world_system == false) {

            ConfigMain.repairAll(level_accessor);
            ConfigMain.apply(level_accessor);

        }

        double cache_size = Cache.clear();

        if (level_accessor == null) {

            if (message == true) {

                TanshugetreesMod.LOGGER.info("Restarted and cleared main caches (" + cache_size + " MB)");

            }

        } else {

            TanshugetreesMod.queueServerWork(20, () -> {

                thread_pause = false;

                if (level_accessor instanceof ServerLevel level_server) {

                    // World Systems
                    {

                        GameUtils.command.run(level_server, 0, 0, 0, "scoreboard objectives add TANSHUGETREES dummy");

                        // Season Detector
                        {

                            if (ConfigMain.serene_seasons_compatibility == true && ModList.get().isLoaded("sereneseasons") == true) {

                                SeasonDetector.start(level_accessor, level_server);

                            }

                        }

                        Loop.start(level_accessor, level_server);

                    }

                    if (message == true) {

                        GameUtils.misc.sendChatMessage(level_server, "@a", "gray", "THT : Restarted and cleared main caches (About " + cache_size + " MB)");

                    }

                }

            });

        }

    }

	@SubscribeEvent
	public static void worldAboutToStart (ServerAboutToStartEvent event) {

        path_world_data = event.getServer().getWorldPath(new LevelResource(".")) + "/data/tanshugetrees";
		restart(null, false, false);

	}

	@SubscribeEvent
	public static void worldStarted (ServerStartedEvent event) {

		restart(event.getServer().overworld(), true, false);

	}

	@SubscribeEvent
	public static void worldStopped (ServerStoppingEvent event) {



	}

	@SubscribeEvent
	public static void playerJoined (PlayerEvent.PlayerLoggedInEvent event) {

		if (GameUtils.misc.playerCount() == 1) {

			TanshugetreesMod.queueServerWork(100, () -> {

				if (ConfigMain.auto_check_update == true) {

					LevelAccessor level_accessor = event.getEntity().level();
					CustomPackIncompatible.scanMain(level_accessor);
					PackCheckUpdate.start(level_accessor, false);

				}

			});

		}

	}

	@SubscribeEvent
	public static void chunkLoaded (ChunkEvent.Load event) {

		if (event.isNewChunk() == true) {

			WorldGenFull.start(event);

		}

	}

}
