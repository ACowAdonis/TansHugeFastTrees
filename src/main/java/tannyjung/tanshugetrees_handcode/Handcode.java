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
import tannyjung.core.Utils;
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
	public static String tanny_pack_version_name = "";

    private static final Object lock = new Object();
    public static boolean system_pause = false;
    public static ExecutorService thread_main = Executors.newFixedThreadPool(1);

    // ----------------------------------------------------------------------------------------------------

	public Handcode () {}

	public static void startGame () {

        TanshugetreesMod.LOGGER.info("Starting...");

        // Thread Start
        {

            thread_main = Executors.newFixedThreadPool(1, name -> {
                Thread thread = new Thread(name);
                thread.setName("Tan's Huge Trees");
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

        restart(null, false);

	}

    public static void restart (LevelAccessor level_accessor, boolean config_repair) {

        system_pause = true;

        Handcode.thread_main.submit(() -> {

            if (config_repair == false) {

                ConfigMain.repairAll(level_accessor);
                ConfigMain.apply();

            }

            double cache_size = Cache.clear();

            if (level_accessor != null) {

                if (level_accessor instanceof ServerLevel level_server) {

                    GameUtils.misc.sendChatMessage(level_server, "@a", "gray", "THT : Restarted and cleared main caches (About " + cache_size + " MB)");

                }

            }

            worldGenNotify();

        });

    }

    public static void worldGenPause () {

        synchronized (lock) {

            while (Handcode.system_pause == true) {

                try {

                    lock.wait();

                } catch (Exception exception) {

                    Utils.misc.exception(new Exception(), exception);

                }

            }

        }

    }

    public static void worldGenNotify () {

        system_pause = false;

        synchronized (lock) {

            lock.notifyAll();

        }

    }

	@SubscribeEvent
	public static void eventWorldAboutToStart(ServerAboutToStartEvent event) {

        path_world_data = event.getServer().getWorldPath(new LevelResource(".")) + "/data/tanshugetrees";
		restart(null, false);

	}

	@SubscribeEvent
	public static void eventWorldStarted(ServerStartedEvent event) {

        ServerLevel level_server = event.getServer().overworld();
        system_pause = true;

        TanshugetreesMod.queueServerWork(20, () -> {

            GameUtils.command.run(level_server, 0, 0, 0, "scoreboard objectives add TANSHUGETREES dummy");
            Loop.start(level_server, level_server);

            if (ConfigMain.serene_seasons_compatibility == true && ModList.get().isLoaded("sereneseasons") == true) {

                SeasonDetector.start(level_server, level_server);

            }

            TanshugetreesMod.LOGGER.info("Started World Systems");
            worldGenNotify();

        });

	}

	@SubscribeEvent
	public static void eventWorldStopped(ServerStoppingEvent event) {



	}

	@SubscribeEvent
	public static void eventPlayerJoined(PlayerEvent.PlayerLoggedInEvent event) {

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
	public static void eventChunkLoaded(ChunkEvent.Load event) {

		if (event.isNewChunk() == true) {

			WorldGenFull.start(event);

		}

	}

}
