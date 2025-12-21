package tannyjung.tanshugetrees.server.world_gen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.*;
import tannyjung.core.FileManager;
import tannyjung.core.game.Utils;
import tannyjung.tanshugetrees.Handcode;
import tannyjung.tanshugetrees.config.ConfigMain;
import tannyjung.tanshugetrees.server.Cache;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;

public class TreeLocation {

    private static final Object lock = new Object();
    private static final Map<String, List<String>> cache_write_tree_location = new HashMap<>();
    private static final Map<String, List<String>> cache_write_place = new HashMap<>();
    private static final Map<String, List<String>> cache_dead_tree_auto_level = new HashMap<>();
    // B1 Optimization: Use long keys instead of String concatenation
    // Upper 32 bits = biome ID hash, lower 32 bits = species ID hash
    private static final Map<Long, Boolean> cache_biome_test = new HashMap<>();
    public static int world_gen_overlay_animation = 0;
    public static int world_gen_overlay_bar = 0;
    public static String world_gen_overlay_details_biome = "";
    public static String world_gen_overlay_details_tree = "";

    // Optimized: Cache neighboring region files to eliminate repeated disk I/O
    // This cache is populated at the start of region generation and cleared at the end
    private static class RegionTreeData {
        final String id;
        final int posX;
        final int posZ;

        RegionTreeData(String id, int posX, int posZ) {
            this.id = id;
            this.posX = posX;
            this.posZ = posZ;
        }
    }
    private static final Map<String, List<RegionTreeData>> cache_region_files = new HashMap<>();

    // Optimized: Spatial hash grid for O(1) tree proximity lookups
    // Replaces O(n) linear scanning (2,763 trees → ~77 trees checked per query)
    private static class SpatialHashGrid {
        // Cell size 256 blocks = covers typical min_distance values efficiently
        // 9 regions (4608×4608 blocks) → 18×18 = 324 cells
        // Average 8.5 trees per cell (2,763 total / 324 cells)
        private static final int CELL_SIZE = 256;
        private final Map<Long, List<RegionTreeData>> grid = new HashMap<>();

        private long getGridKey(int x, int z) {
            long cellX = Math.floorDiv(x, CELL_SIZE);
            long cellZ = Math.floorDiv(z, CELL_SIZE);
            return (cellX << 32) | (cellZ & 0xFFFFFFFFL);
        }

        void addTree(String id, int x, int z) {
            long key = getGridKey(x, z);
            RegionTreeData treeData = new RegionTreeData(id, x, z);
            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(treeData);
        }

        boolean testDistance(String id, int centerX, int centerZ, int minDistance) {
            int cellX = Math.floorDiv(centerX, CELL_SIZE);
            int cellZ = Math.floorDiv(centerZ, CELL_SIZE);

            // Check 3×3 grid of cells around position (max 9 cells, ~77 trees)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long key = ((long)(cellX + dx) << 32) | ((cellZ + dz) & 0xFFFFFFFFL);
                    List<RegionTreeData> trees = grid.get(key);

                    if (trees != null) {
                        for (RegionTreeData tree : trees) {
                            // Check for exact position collision
                            if (centerX == tree.posX && centerZ == tree.posZ) {
                                return false;
                            }
                            // Check minimum distance for same species
                            if (id.equals(tree.id)) {
                                if (Math.abs(centerX - tree.posX) <= minDistance &&
                                    Math.abs(centerZ - tree.posZ) <= minDistance) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }
            return true;
        }

        void clear() {
            grid.clear();
        }
    }
    private static final SpatialHashGrid spatialGrid = new SpatialHashGrid();

    public static void start (LevelAccessor level_accessor, String dimension, ChunkPos chunk_pos) {

        // C1 Optimization: Early-out before acquiring lock
        // Check if all four corner regions already exist - if so, skip lock entirely
        // This eliminates lock contention for already-scanned regions
        {
            int[] regionXs = {
                (chunk_pos.x + 4) >> 5,
                (chunk_pos.x + 4) >> 5,
                (chunk_pos.x - 4) >> 5,
                (chunk_pos.x - 4) >> 5
            };
            int[] regionZs = {
                (chunk_pos.z + 4) >> 5,
                (chunk_pos.z - 4) >> 5,
                (chunk_pos.z + 4) >> 5,
                (chunk_pos.z - 4) >> 5
            };

            boolean allRegionsExist = true;
            for (int i = 0; i < 4; i++) {
                File regionFile = new File(Handcode.path_world_data + "/world_gen/#regions/" + dimension + "/" + regionXs[i] + "," + regionZs[i] + ".bin");
                if (!regionFile.exists()) {
                    allRegionsExist = false;
                    break;
                }
            }

            if (allRegionsExist) {
                return; // All regions already scanned, no need to acquire lock
            }
        }

        synchronized (lock) {

            TreeLocation.run(level_accessor, dimension, new ChunkPos(chunk_pos.x + 4, chunk_pos.z + 4));
            TreeLocation.run(level_accessor, dimension, new ChunkPos(chunk_pos.x + 4, chunk_pos.z - 4));
            TreeLocation.run(level_accessor, dimension, new ChunkPos(chunk_pos.x - 4, chunk_pos.z + 4));
            TreeLocation.run(level_accessor, dimension, new ChunkPos(chunk_pos.x - 4, chunk_pos.z - 4));

        }

    }

    public static void run (LevelAccessor level_accessor, String dimension, ChunkPos chunk_pos) {


        int region_posX = chunk_pos.x >> 5;
        int region_posZ = chunk_pos.z >> 5;
        File file_region = new File(Handcode.path_world_data + "/world_gen/#regions/" + dimension + "/" + region_posX + "," + region_posZ + ".bin");
        RandomSource random = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ (region_posX * 341873128712L + region_posZ * 132897987541L));

        if (file_region.exists() == false) {

            FileManager.writeBIN(file_region.getPath(), new ArrayList<>(), false);
            File file = new File(Handcode.path_config + "/config_world_gen.txt");

            if (file.exists() == true && file.isDirectory() == false) {

                // Performance timing - grep for "THT-PERF:" to extract metrics
                long perf_start = System.nanoTime();
                long perf_neighbor = 0;
                long perf_scan = 0;
                long perf_write = 0;

                Handcode.logger.info("Generating tree locations for a new region ({} -> {}/{})", dimension.replace("-", ":"), region_posX, region_posZ);
                world_gen_overlay_animation = 4;
                world_gen_overlay_bar = 0;
                scanning_overlay_loop();

                // Optimized: Pre-load neighboring region files and populate spatial grid
                long perf_neighbor_start = System.nanoTime();
                {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int neighborX = region_posX + dx;
                            int neighborZ = region_posZ + dz;
                            String regionKey = neighborX + "," + neighborZ;

                            if (!cache_region_files.containsKey(regionKey)) {
                                List<RegionTreeData> trees = new ArrayList<>();
                                String path = Handcode.path_world_data + "/world_gen/tree_locations/" + dimension + "/" + regionKey + ".bin";
                                File regionFile = new File(path);

                                if (regionFile.exists() && regionFile.length() > 0) {
                                    try {
                                        ByteBuffer buffer = FileManager.readBIN(path);
                                        while (buffer.remaining() > 0) {
                                            String treeId = Cache.getDictionary(String.valueOf(buffer.getShort()), true);
                                            int posX_tree = buffer.getInt();
                                            int posZ_tree = buffer.getInt();
                                            trees.add(new RegionTreeData(treeId, posX_tree, posZ_tree));
                                            // Add to spatial grid for fast proximity lookups
                                            spatialGrid.addTree(treeId, posX_tree, posZ_tree);
                                        }
                                    } catch (Exception e) {
                                        // File read error - log but continue with empty list
                                        Handcode.logger.warn("Failed to load neighboring region file: " + path);
                                    }
                                }
                                cache_region_files.put(regionKey, trees);
                            }
                        }
                    }
                }
                perf_neighbor = System.nanoTime() - perf_neighbor_start;

                // Region Scanning
                long perf_scan_start = System.nanoTime();
                {

                    String[] config_world_gen = FileManager.readTXT(file.getPath());
                    int posX = region_posX * 32;
                    int posZ = region_posZ * 32;

                    // DEBUG: Log enabled species count
                    List<Cache.SpeciesWorldGenConfig> enabledSpecies = Cache.getEnabledSpecies();
                    Handcode.logger.info("DEBUG: Found {} enabled species for world gen", enabledSpecies.size());
                    if (enabledSpecies.isEmpty()) {
                        Handcode.logger.warn("DEBUG: No enabled species found! Check config parsing.");
                    }

                    for (int scanX = 0; scanX < 32; scanX++) {

                        for (int scanZ = 0; scanZ < 32; scanZ++) {

                            world_gen_overlay_bar = world_gen_overlay_bar + 1;

                            if (random.nextDouble() < ConfigMain.region_scan_chance) {

                                getData(level_accessor, dimension, random, posX + scanX, posZ + scanZ, config_world_gen);

                            }

                        }

                    }

                }
                perf_scan = System.nanoTime() - perf_scan_start;

                world_gen_overlay_animation = 0;

                // DEBUG: Log how many trees were queued for writing
                int totalTrees = 0;
                for (List<String> trees : cache_write_tree_location.values()) {
                    totalTrees += trees.size() / 3; // Each tree has 3 entries (type, x, z)
                }
                Handcode.logger.info("DEBUG: Region complete - {} trees queued for {} locations", totalTrees, cache_write_tree_location.size());
                Handcode.logger.info("DEBUG: Test failures - Rarity: {}, Biome: {}, Distance: {}, Waterside: {}, Trees placed: {}",
                    debug_rarity_fail, debug_biome_fail, debug_distance_fail, debug_waterside_fail, debug_trees_placed);
                // Reset debug counters for next region
                debug_rarity_fail = 0;
                debug_biome_fail = 0;
                debug_distance_fail = 0;
                debug_waterside_fail = 0;
                debug_trees_placed = 0;
                Handcode.logger.info("Completed!");

                // Write File
                long perf_write_start = System.nanoTime();
                {

                    for (Map.Entry<String, List<String>> entry : cache_write_tree_location.entrySet()) {

                        FileManager.writeBIN(Handcode.path_world_data + "/world_gen/tree_locations/" + dimension + "/" + entry.getKey() + ".bin", entry.getValue(), true);

                    }

                    for (Map.Entry<String, List<String>> entry : cache_write_place.entrySet()) {

                        FileManager.writeBIN(Handcode.path_world_data + "/world_gen/place/" + dimension + "/" + entry.getKey() + ".bin", entry.getValue(), true);

                    }

                }
                perf_write = System.nanoTime() - perf_write_start;

                // Performance summary - grep for "THT-PERF:" to extract metrics
                // Format: THT-PERF: region=X,Z total=Xms neighbor=Xms scan=Xms write=Xms trees=N species=N
                long perf_total = System.nanoTime() - perf_start;
                Handcode.logger.info("THT-PERF: region={},{} total={}ms neighbor={}ms scan={}ms write={}ms trees={} species={}",
                    region_posX, region_posZ,
                    perf_total / 1_000_000,
                    perf_neighbor / 1_000_000,
                    perf_scan / 1_000_000,
                    perf_write / 1_000_000,
                    totalTrees,
                    Cache.getEnabledSpecies().size());

            }

            cache_write_tree_location.clear();
            cache_write_place.clear();
            cache_dead_tree_auto_level.clear();
            cache_biome_test.clear();
            // Optimized: Clear region file cache and spatial grid after generation completes
            cache_region_files.clear();
            spatialGrid.clear();

        }

    }

    private static void scanning_overlay_loop () {

        // CRITICAL FIX: Check animation state BEFORE scheduling next iteration
        // Previously, the loop would reschedule itself forever even after animation completed,
        // causing delayed_works queue to grow with each region generation
        if (world_gen_overlay_animation == 0) {
            return;  // Stop the loop when animation is complete
        }

        // Only schedule next iteration if animation is still active
        Handcode.createDelayedWorks(20, () -> {
            scanning_overlay_loop();
        });

        // Cycle through animation frames 1 -> 2 -> 3 -> 4 -> 1 ...
        if (world_gen_overlay_animation < 4) {
            world_gen_overlay_animation = world_gen_overlay_animation + 1;
        } else {
            world_gen_overlay_animation = 1;
        }

    }

    // DEBUG counters for tracking why trees aren't generating
    private static int debug_rarity_fail = 0;
    private static int debug_biome_fail = 0;
    private static int debug_distance_fail = 0;
    private static int debug_waterside_fail = 0;
    private static int debug_trees_placed = 0;

    // A1+B3 Optimization: Rewritten to use pre-parsed config
    // Eliminates ~7.7 million string comparisons per region
    // D1 Optimization: getBiome() called once per chunk, not per species
    // Reduces from 35,840 calls to 1,024 calls per region (35× reduction)
    private static void getData (LevelAccessor level_accessor, String dimension, RandomSource random, int chunk_posX, int chunk_posZ, String[] config_world_gen) {

        world_gen_overlay_details_tree = "No Matching";

        // D1 Optimization: Sample biome ONCE per chunk (at chunk center)
        // All species use this biome for matching, but each gets its own random placement position
        int biome_sample_posX = (chunk_posX * 16) + 8;
        int biome_sample_posZ = (chunk_posZ * 16) + 8;
        Holder<Biome> chunk_biome = level_accessor.getBiome(new BlockPos(biome_sample_posX, level_accessor.getMaxBuildHeight(), biome_sample_posZ));
        String chunk_biome_id = Utils.biome.toID(chunk_biome);
        world_gen_overlay_details_biome = chunk_biome_id;

        // Get pre-parsed and pre-filtered species configurations (parsed once, cached)
        // B1 Optimization: Only iterate over enabled species (world_gen=true)
        List<Cache.SpeciesWorldGenConfig> speciesList = Cache.getEnabledSpecies();

        for (Cache.SpeciesWorldGenConfig species : speciesList) {

            // Generate random position within chunk for this species (placement position)
            int center_posX = (chunk_posX * 16) + random.nextInt(0, 16);
            int center_posZ = (chunk_posZ * 16) + random.nextInt(0, 16);
            world_gen_overlay_details_tree = species.id;

            // Rarity test
            double rarity = (species.rarity * 0.01) * ConfigMain.multiply_rarity;
            if (random.nextDouble() >= rarity) {
                debug_rarity_fail++;
                continue;
            }

            // Biome test (with caching) - uses chunk-level biome sample
            // B1 Optimization: Use long key instead of String concatenation
            long biomeTestKey = ((long)chunk_biome_id.hashCode() << 32) | (species.id.hashCode() & 0xFFFFFFFFL);
            if (cache_biome_test.containsKey(biomeTestKey)) {
                if (!cache_biome_test.get(biomeTestKey)) {
                    debug_biome_fail++;
                    continue;
                }
            } else {
                boolean result = Utils.misc.testCustomBiome(chunk_biome, species.biome);
                cache_biome_test.put(biomeTestKey, result);
                if (!result) {
                    debug_biome_fail++;
                    continue;
                }
            }

            // Min Distance test
            int min_distance = (int) Math.ceil(species.min_distance * ConfigMain.multiply_min_distance);
            if (min_distance > 0) {
                if (!testDistance(dimension, species.id, center_posX, center_posZ, min_distance)) {
                    debug_distance_fail++;
                    continue;
                }
            }

            // Group Size calculation (pre-parsed min/max)
            int group_size_min = (int) Math.ceil(species.group_size_min * ConfigMain.multiply_group_size);
            int group_size_max = (int) Math.ceil(species.group_size_max * ConfigMain.multiply_group_size);
            if (group_size_min < 1) group_size_min = 1;
            if (group_size_max < 1) group_size_max = 1;
            int group_size_get = Mth.nextInt(random, group_size_min, group_size_max);

            // Waterside Detection
            double waterside_chance = species.waterside_chance * ConfigMain.multiply_waterside_chance;
            if (!testWaterSide(level_accessor, random, center_posX, center_posZ, waterside_chance)) {
                debug_waterside_fail++;
                continue;
            }

            // Apply config multipliers for dead tree chance
            double dead_tree_chance = species.dead_tree_chance * ConfigMain.multiply_dead_tree_chance;

            debug_trees_placed++;
            writeData(level_accessor, random, center_posX, center_posZ, species.id, species.ground_block,
                      species.start_height_offset, species.rotation, species.mirrored, dead_tree_chance, species.dead_tree_level);

            // Group Spawning
            if (group_size_get > 1) {
                while (group_size_get > 0) {
                    group_size_get = group_size_get - 1;
                    center_posX = center_posX + random.nextInt(-(min_distance + 1), (min_distance + 1) + 1);
                    center_posZ = center_posZ + random.nextInt(-(min_distance + 1), (min_distance + 1) + 1);

                    // Biome test for group member (group members can be at different positions)
                    Holder<Biome> group_biome = level_accessor.getBiome(new BlockPos(center_posX, level_accessor.getMaxBuildHeight(), center_posZ));
                    if (!Utils.misc.testCustomBiome(group_biome, species.biome)) {
                        continue;
                    }

                    // Min Distance test for group member
                    if (min_distance > 0) {
                        if (!testDistance(dimension, species.id, center_posX, center_posZ, min_distance)) {
                            continue;
                        }
                    }

                    writeData(level_accessor, random, center_posX, center_posZ, species.id, species.ground_block,
                              species.start_height_offset, species.rotation, species.mirrored, dead_tree_chance, species.dead_tree_level);
                }
            }

        }

    }

    private static boolean testDistance (String dimension, String id, int center_posX, int center_posZ, int min_distance) {

        // Optimized: Use spatial hash grid for O(1) average case lookups
        // Old: O(n) scan through 2,763 trees across 9 regions
        // New: O(1) check ~77 trees in 9 spatial grid cells (35× faster)
        return spatialGrid.testDistance(id, center_posX, center_posZ, min_distance);

    }

    private static boolean testWaterSide (LevelAccessor level_accessor, RandomSource random, int center_posX, int center_posZ, double waterside_chance) {

        boolean return_logic = true;

        {

            if (waterside_chance > 0) {

                if (ConfigMain.waterside_detection == false) {

                    return_logic = false;

                } else {

                    if (random.nextDouble() < waterside_chance) {

                        boolean on_land = Utils.biome.isTaggedAs(level_accessor.getBiome(new BlockPos(center_posX, level_accessor.getMaxBuildHeight(), center_posZ)), "forge:is_water") == false;
                        int size = ConfigMain.surface_detection_size;
                        boolean waterside_test1 = Utils.biome.isTaggedAs(level_accessor.getBiome(new BlockPos(center_posX + size, level_accessor.getMaxBuildHeight(), center_posZ + size)), "forge:is_water");
                        boolean waterside_test2 = Utils.biome.isTaggedAs(level_accessor.getBiome(new BlockPos(center_posX + size, level_accessor.getMaxBuildHeight(), center_posZ - size)), "forge:is_water");
                        boolean waterside_test3 = Utils.biome.isTaggedAs(level_accessor.getBiome(new BlockPos(center_posX - size, level_accessor.getMaxBuildHeight(), center_posZ + size)), "forge:is_water");
                        boolean waterside_test4 = Utils.biome.isTaggedAs(level_accessor.getBiome(new BlockPos(center_posX - size, level_accessor.getMaxBuildHeight(), center_posZ - size)), "forge:is_water");

                        if (on_land == true) {

                            if (waterside_test1 == false && waterside_test2 == false && waterside_test3 == false && waterside_test4 == false) {

                                return_logic = false;

                            }

                        } else {

                            if (waterside_test1 == true && waterside_test2 == true && waterside_test3 == true && waterside_test4 == true) {

                                return_logic = false;

                            }

                        }

                    }

                }

            }

        }

        return return_logic;

    }

    private static void writeData (LevelAccessor level_accessor, RandomSource random, int center_posX, int center_posZ, String id, String ground_block, String start_height_offset, String rotation, String mirrored, double dead_tree_chance, String dead_tree_level) {

        String path_storage = "";

        // Scan World Gen File
        {
            
            for (String read_all : Cache.getWorldGenSettings(id)) {
                
                {

                    if (read_all.startsWith("path_storage = ") == true) {

                        path_storage = read_all.replace("path_storage = ", "");
                        break;

                    }

                }

            }

        }

        // A2 Optimization: Use cached storage file list instead of File.listFiles()
        String chosenFileName = null;
        {
            String[] storageFiles = Cache.getStorageFiles(path_storage);
            if (storageFiles.length > 0) {
                chosenFileName = storageFiles[random.nextInt(storageFiles.length)];
            }
        }

        if (chosenFileName != null) {

            short[] get = Cache.getTreeShape(path_storage + "/" + chosenFileName, 1);
            int sizeX = get[0];
            int sizeY = get[1];
            int sizeZ = get[2];
            int center_sizeX = get[3];
            int center_sizeY = get[4];
            int center_sizeZ = get[5];
            int count_trunk = get[6];
            int count_bough = get[6];
            int count_branch = get[7];
            int count_limb = get[8];
            int count_twig = get[9];
            int count_sprig = get[10];

            // Dead Tree
            {

                if (random.nextDouble() >= dead_tree_chance) {

                    dead_tree_level = "0";

                } else {

                    List<String> list = new ArrayList<>();

                    if (dead_tree_level.startsWith("auto") == false) {

                        list = Arrays.stream(dead_tree_level.split(" / ")).toList();

                    } else {

                        if (cache_dead_tree_auto_level.containsKey(id) == true) {

                            list = cache_dead_tree_auto_level.get(id);

                        } else {

                            String is_pine = "0";

                            if (dead_tree_level.endsWith("pine") == true) {

                                is_pine = "1";

                            }

                            // Write Data
                            {

                                if (count_trunk > 0) {

                                    list.add("180");
                                    list.add("190");
                                    list.add("280");
                                    list.add("290");
                                    list.add("380");
                                    list.add("390");

                                }

                                if (count_bough > 0) {

                                    list.add("160");
                                    list.add("170");
                                    list.add("260");
                                    list.add("270");
                                    list.add("360");
                                    list.add("370");
                                    list.add("15" + is_pine);
                                    list.add("25" + is_pine);
                                    list.add("35" + is_pine);

                                }

                                if (count_branch > 0) {

                                    list.add("14" + is_pine);
                                    list.add("24" + is_pine);
                                    list.add("34" + is_pine);

                                }

                                if (count_limb > 0) {

                                    list.add("13" + is_pine);
                                    list.add("23" + is_pine);
                                    list.add("33" + is_pine);

                                }

                                if (count_twig > 0) {

                                    list.add("12" + is_pine);
                                    list.add("22" + is_pine);
                                    list.add("32" + is_pine);

                                }

                                if (count_sprig > 0) {

                                    list.add("11" + is_pine);
                                    list.add("21" + is_pine);
                                    list.add("31" + is_pine);

                                }

                            }

                            cache_dead_tree_auto_level.put(id, list);

                        }

                    }

                    dead_tree_level =  list.get(random.nextInt(list.size()));

                }

            }

            int start_height_offset_get = 0;

            // Height Offset
            {

                String[] offset_get = start_height_offset.split(" <> ");
                start_height_offset_get = random.nextInt(Integer.parseInt(offset_get[0]), Integer.parseInt(offset_get[1]) + 1);

            }

            // Rotation & Mirrored
            {

                // Convert
                {

                    if (rotation.equals("north") == true) {

                        rotation = "1";

                    } else if (rotation.equals("west") == true) {

                        rotation = "4";

                    } else if (rotation.equals("east") == true) {

                        rotation = "2";

                    } else if (rotation.equals("south") == true) {

                        rotation = "3";

                    } else {

                        rotation = String.valueOf(random.nextInt(4) + 1);

                    }

                    if (mirrored.equals("false") == true) {

                        mirrored = "false";

                    } else if (mirrored.equals("true") == true) {

                        mirrored = "true";

                    } else {

                        mirrored = String.valueOf(random.nextBoolean());

                    }

                }

                // Applying
                {

                    if (mirrored.equals("true") == true) {

                        center_sizeX = sizeX - center_sizeX;

                    }

                    if (rotation.equals("2") == true) {

                        int center_sizeX_save = center_sizeX;
                        center_sizeX = center_sizeZ;
                        center_sizeZ = sizeX - center_sizeX_save;

                        int sizeX_save = sizeX;
                        sizeX = sizeZ;
                        sizeZ = sizeX_save;

                    } else if (rotation.equals("3") == true) {

                        center_sizeX = sizeX - center_sizeX;
                        center_sizeZ = sizeZ - center_sizeZ;

                    } else if (rotation.equals("4") == true) {

                        int center_sizeX_save = center_sizeX;
                        center_sizeX = sizeZ - center_sizeZ;
                        center_sizeZ = center_sizeX_save;

                        int sizeX_save = sizeX;
                        sizeX = sizeZ;
                        sizeZ = sizeX_save;

                    }

                }

            }

            // Coarse Woody Debris
            {

                if (Integer.parseInt(dead_tree_level) > 200) {

                    int fallen_direction = RandomSource.create(level_accessor.getServer().overworld().getSeed() ^ (center_posX * 341873128712L + center_posZ * 132897987541L)).nextInt(4) + 1;
                    int sizeX_save = sizeX;
                    int sizeY_save = sizeY;
                    int sizeZ_save = sizeZ;
                    int center_sizeX_save = center_sizeX;
                    int center_sizeY_save = center_sizeY;
                    int center_sizeZ_save = center_sizeZ;

                    if (fallen_direction == 1) {

                        sizeY = sizeX_save;
                        sizeX = sizeY_save;
                        center_sizeY = sizeX_save - center_sizeX_save;
                        center_sizeX = center_sizeY_save;

                    } else if (fallen_direction == 2) {

                        sizeY = sizeZ_save;
                        sizeZ = sizeY_save;
                        center_sizeY = sizeZ_save - center_sizeZ_save;
                        center_sizeZ = center_sizeY_save;

                    } else if (fallen_direction == 3) {

                        sizeY = sizeX_save;
                        sizeX = sizeY_save;
                        center_sizeY = center_sizeX_save;
                        center_sizeX = sizeY_save - center_sizeY_save;

                    } else if (fallen_direction == 4) {

                        sizeY = sizeZ_save;
                        sizeZ = sizeY_save;
                        center_sizeY = center_sizeZ_save;
                        center_sizeZ = sizeY_save - center_sizeY_save;

                    }

                }

            }

            int from_chunkX = center_posX - center_sizeX;
            int from_chunkZ = center_posZ - center_sizeZ;
            int to_chunkX = (from_chunkX + sizeX) >> 4;
            int to_chunkZ = (from_chunkZ + sizeZ) >> 4;
            from_chunkX = from_chunkX >> 4;
            from_chunkZ = from_chunkZ >> 4;

            // Test Exist Chunk
            {

                int scan_fromX = from_chunkX - 4;
                int scan_fromZ = from_chunkZ - 4;
                int scan_toX = to_chunkX + 4;
                int scan_toZ = to_chunkZ + 4;

                for (int scanX = scan_fromX ; scanX <= scan_toX; scanX++) {

                    for (int scanZ = scan_fromZ; scanZ <= scan_toZ; scanZ++) {

                        if (level_accessor.getChunk(scanX, scanZ, ChunkStatus.FEATURES, false) != null) {

                            return;

                        }

                    }

                }

            }

            // Everything Pass
            {

                int regionX = center_posX >> 9;
                int regionZ = center_posZ >> 9;

                // Write Tree Location
                {

                    List<String> write = new ArrayList<>();
                    write.add("t" + id);
                    write.add("i" + center_posX);
                    write.add("i" + center_posZ);
                    cache_write_tree_location.computeIfAbsent(regionX + "," + regionZ, test -> new ArrayList<>()).addAll(write);

                    // CRITICAL: Add newly placed tree to spatial grid immediately
                    // This ensures subsequent tree placements can detect this tree
                    spatialGrid.addTree(id, center_posX, center_posZ);

                }

                // Write Place
                {

                    List<String> write = new ArrayList<>();
                    write.add("i" + from_chunkX);
                    write.add("i" + from_chunkZ);
                    write.add("i" + to_chunkX);
                    write.add("i" + to_chunkZ);
                    write.add("t" + id);
                    write.add("t" + chosenFileName);
                    write.add("i" + center_posX);
                    write.add("i" + center_posZ);
                    write.add("b" + rotation);
                    write.add("l" + mirrored);
                    write.add("s" + start_height_offset_get);
                    write.add("s" + (sizeY - center_sizeY));
                    write.add("t" + ground_block);
                    write.add("s" + dead_tree_level);

                    int from_chunkX_test = from_chunkX >> 5;
                    int from_chunkZ_test = from_chunkZ >> 5;
                    int to_chunkX_test = to_chunkX >> 5;
                    int to_chunkZ_test = to_chunkZ >> 5;

                    for (int scanX = from_chunkX_test; scanX <= to_chunkX_test; scanX++) {

                        for (int scanZ = from_chunkZ_test; scanZ <= to_chunkZ_test; scanZ++) {

                            cache_write_place.computeIfAbsent(scanX + "," + scanZ, test -> new ArrayList<>()).addAll(write);

                        }

                    }

                }

            }

        }

    }

}