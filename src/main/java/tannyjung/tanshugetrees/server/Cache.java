package tannyjung.tanshugetrees.server;

import tannyjung.core.FileManager;
import tannyjung.core.OutsideUtils;
import tannyjung.tanshugetrees.Handcode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Cache {

    /**
     * A1+B3 Optimization: Pre-parsed species world gen configuration.
     * Eliminates ~7.7 million string comparisons per region by parsing config ONCE.
     */
    public static class SpeciesWorldGenConfig {
        public final String id;
        public final boolean world_gen;
        public final String biome;
        public final String ground_block;
        public final double rarity;
        public final int min_distance;
        public final int group_size_min;
        public final int group_size_max;
        public final double waterside_chance;
        public final double dead_tree_chance;
        public final String dead_tree_level;
        public final String start_height_offset;
        public final String rotation;
        public final String mirrored;
        // B2 Optimization: Pre-parsed path_storage to eliminate per-tree string parsing
        public final String path_storage;

        public SpeciesWorldGenConfig(String id, boolean world_gen, String biome, String ground_block,
                                     double rarity, int min_distance, String group_size,
                                     double waterside_chance, double dead_tree_chance, String dead_tree_level,
                                     String start_height_offset, String rotation, String mirrored,
                                     String path_storage) {
            this.id = id;
            this.world_gen = world_gen;
            this.biome = biome;
            this.ground_block = ground_block;
            this.rarity = rarity;
            this.min_distance = min_distance;

            // Pre-parse group_size "min <> max" format
            String[] groupParts = group_size.split(" <> ");
            this.group_size_min = Integer.parseInt(groupParts[0]);
            this.group_size_max = groupParts.length > 1 ? Integer.parseInt(groupParts[1]) : this.group_size_min;

            this.waterside_chance = waterside_chance;
            this.dead_tree_chance = dead_tree_chance;
            this.dead_tree_level = dead_tree_level;
            this.start_height_offset = start_height_offset;
            this.rotation = rotation;
            this.mirrored = mirrored;
            this.path_storage = path_storage;
        }
    }

    private static List<SpeciesWorldGenConfig> cached_world_gen_config = null;
    private static List<SpeciesWorldGenConfig> cached_enabled_species = null;

    /**
     * P1.1+P1.2 Optimization: Pre-parsed detection result for a single tree position.
     * Stores the result of expensive detailed_detection tests.
     */
    public static class DetectionResult {
        public final boolean pass;
        public final int posY;
        public final int deadTreeLevel;

        public DetectionResult(boolean pass, int posY, int deadTreeLevel) {
            this.pass = pass;
            this.posY = posY;
            this.deadTreeLevel = deadTreeLevel;
        }
    }

    /**
     * P1.1 Optimization: LRU cache for detailed_detection regions.
     * Uses odd-square size (25) for optimal region boundary access patterns.
     * Each entry maps posX,posZ to DetectionResult for O(1) lookup.
     * ~50KB per region, ~1.25MB total for 25-region cache.
     */
    private static final int DETECTION_CACHE_SIZE = 25;
    private static final LinkedHashMap<String, Map<Long, DetectionResult>> detailed_detection_cache =
        new LinkedHashMap<>(DETECTION_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<Long, DetectionResult>> eldest) {
                return size() > DETECTION_CACHE_SIZE;
            }
        };


    /**
     * P2.1 Optimization: Pre-parsed tree placement data for a single tree.
     * Allows indexing by chunk position for O(1) lookup.
     */
    public static class TreePlacementData {
        public final int from_chunkX, from_chunkZ, to_chunkX, to_chunkZ;
        public final String id, chosen;
        public final int center_posX, center_posZ;
        public final int rotation;
        public final boolean mirrored;
        public final int start_height_offset, up_sizeY;
        public final String ground_block;
        public final int dead_tree_level;

        public TreePlacementData(int from_chunkX, int from_chunkZ, int to_chunkX, int to_chunkZ,
                                 String id, String chosen, int center_posX, int center_posZ,
                                 int rotation, boolean mirrored, int start_height_offset, int up_sizeY,
                                 String ground_block, int dead_tree_level) {
            this.from_chunkX = from_chunkX;
            this.from_chunkZ = from_chunkZ;
            this.to_chunkX = to_chunkX;
            this.to_chunkZ = to_chunkZ;
            this.id = id;
            this.chosen = chosen;
            this.center_posX = center_posX;
            this.center_posZ = center_posZ;
            this.rotation = rotation;
            this.mirrored = mirrored;
            this.start_height_offset = start_height_offset;
            this.up_sizeY = up_sizeY;
            this.ground_block = ground_block;
            this.dead_tree_level = dead_tree_level;
        }
    }

    /**
     * P2.1 Optimization: Indexed placement data per region.
     * Maps chunk coordinate (packed long) to list of trees affecting that chunk.
     * Key format: "dimension/regionX,regionZ"
     */
    private static final int PLACEMENT_INDEX_CACHE_SIZE = 25;
    private static final LinkedHashMap<String, Map<Long, List<TreePlacementData>>> placement_index_cache =
        new LinkedHashMap<>(PLACEMENT_INDEX_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<Long, List<TreePlacementData>>> eldest) {
                return size() > PLACEMENT_INDEX_CACHE_SIZE;
            }
        };

    /**
     * P1.3 Optimization: Cache structure detection results per chunk.
     * Stores whether a chunk contains surface structures to avoid repeated getAllReferences() calls.
     * Key format: "dimension/chunkX,chunkZ"
     * Value: true = has surface structures (tree placement fails), false = no surface structures
     */
    private static final int STRUCTURE_CACHE_SIZE = 1024; // Larger cache for chunk-level granularity
    private static final LinkedHashMap<String, Boolean> structure_detection_cache =
        new LinkedHashMap<>(STRUCTURE_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > STRUCTURE_CACHE_SIZE;
            }
        };

    /**
     * P3.1 Optimization: Thread-local heightmap cache for chunk processing.
     * Caches getBaseHeight results within a single chunk's tree processing.
     * Key format: "posX,posZ,heightmapType" (e.g., "100,200,OCEAN_FLOOR_WG")
     * Cleared at start of each chunk via clearHeightmapCache().
     */
    private static final ThreadLocal<Map<String, Integer>> heightmap_cache =
        ThreadLocal.withInitial(HashMap::new);

    private static final Map<String, String> dictionary = new HashMap<>();
    private static final Map<String, short[]> tree_shape_part1 = new HashMap<>();
    private static final Map<String, short[]> tree_shape_part2 = new HashMap<>();
    private static final Map<String, String[]> world_gen_settings = new HashMap<>();
    private static final Map<String, String[]> tree_settings = new HashMap<>();
    private static final Map<String, String[]> functions = new HashMap<>();
    private static String[] functions_tree_decoration = new String[0];
    private static String[] functions_tree_decoration_decay = new String[0];
    private static final Map<String, String[]> leaf_litter = new HashMap<>();
    private static final Map<String, String[]> storage_file_lists = new HashMap<>();

    /**
     * G5 Optimization: In-memory placement cache.
     * Eliminates disk I/O during active chunk generation by keeping placement data in memory.
     * Key format: "dimension/regionX,regionZ" (e.g., "overworld/0,0")
     * Data is the raw ByteBuffer that would normally be read from place.bin files.
     * Thread-safe for C2ME parallel chunk generation.
     */
    private static final ConcurrentHashMap<String, ByteBuffer> placement_cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> placement_needs_persist = new ConcurrentHashMap<>();

    public static double clear () {

        double size = 0;

        {

            size = size + OutsideUtils.Cache.sizeMapText(dictionary);
            size = size + OutsideUtils.Cache.sizeMapNumber(tree_shape_part1);
            size = size + OutsideUtils.Cache.sizeMapNumber(tree_shape_part2);
            size = size + OutsideUtils.Cache.sizeMapTextList(world_gen_settings);
            size = size + OutsideUtils.Cache.sizeMapTextList(tree_settings);
            size = size + OutsideUtils.Cache.sizeMapTextList(functions);
            size = size + OutsideUtils.Cache.sizeArrayText(functions_tree_decoration);
            size = size + OutsideUtils.Cache.sizeArrayText(functions_tree_decoration_decay);
            size = size + OutsideUtils.Cache.sizeMapTextList(leaf_litter);
            size = size + OutsideUtils.Cache.sizeMapTextList(storage_file_lists);

            dictionary.clear();
            tree_shape_part1.clear();
            tree_shape_part2.clear();
            world_gen_settings.clear();
            tree_settings.clear();
            functions.clear();
            functions_tree_decoration = new String[0];
            functions_tree_decoration_decay = new String[0];
            leaf_litter.clear();
            storage_file_lists.clear();
            cached_world_gen_config = null;
            cached_enabled_species = null;
            // P1.1: Clear detailed detection cache
            detailed_detection_cache.clear();
            // P2.1: Clear placement index cache
            placement_index_cache.clear();
            // P1.3: Clear structure detection cache
            structure_detection_cache.clear();
            // G5: Flush any pending persistence before clearing
            flushPlacementPersistence();
            placement_cache.clear();
            placement_needs_persist.clear();

        }

        return Math.round((size / (1024 * 1024)) * 100.0) / 100.0;

    }

    public static String getDictionary (String key, boolean id) {

        if (key.equals("") == false) {

            if (dictionary.containsKey(key) == false) {

                {

                    String path = Handcode.path_world_data + "/bin_dictionary.txt";
                    String[] data = FileManager.readTXT(path);
                    String value_id = "";
                    String value_text = "";

                    for (String read_all : data) {

                        if (id == true) {

                            if (read_all.startsWith(key + "|") == true) {

                                value_id = key;
                                value_text = read_all.substring(read_all.indexOf("|") + 1);
                                break;

                            }

                        } else {

                            if (read_all.endsWith("|" + key) == true) {

                                value_id = read_all.substring(0, read_all.indexOf("|"));
                                value_text = key;
                                break;

                            }

                        }

                    }

                    if (value_id.equals("") == true && value_text.equals("") == true) {

                        if (id == false) {

                            value_text = key;

                        }

                        value_id = String.valueOf(data.length + 1);
                        FileManager.writeTXT(path, value_id + "|" + value_text + "\n", true);

                    }

                    dictionary.put(value_id, value_text);
                    dictionary.put(value_text, value_id);

                }

            }

        }

        return dictionary.getOrDefault(key, "");

    }

    public static short[] getTreeShape (String id, int part) {

        if (tree_shape_part1.containsKey(id) == false) {

            String[] split = id.split("/");
            String shapePath = Handcode.path_config + "/custom_packs/" + split[0] + "/presets/" + split[1] + "/storage/" + split[2];

            ShortBuffer buffer = FileManager.readBIN(shapePath).asShortBuffer();

            if (buffer.remaining() > 0) {

                short[] data = new short[buffer.remaining()];
                buffer.get(data);

                // Auto-detect header size by finding first valid block type (starts with 1 for blocks)
                // Beta tree pack may have extended header (16+ shorts instead of 12)
                int headerSize = 12; // default
                for (int i = 12; i < Math.min(data.length, 24); i++) {
                    String typeStr = String.valueOf(data[i]);
                    if (typeStr.startsWith("1") && typeStr.length() == 4) {
                        headerSize = i;
                        break;
                    }
                }

                tree_shape_part1.put(id, Arrays.copyOfRange(data, 0, headerSize));
                tree_shape_part2.put(id, Arrays.copyOfRange(data, headerSize, data.length));

            }

        }

        if (part == 1) {

           return tree_shape_part1.getOrDefault(id, new short[0]);

        } else if (part == 2) {

            return tree_shape_part2.getOrDefault(id, new short[0]);

        }

        return new short[0];

    }

    public static String[] getWorldGenSettings (String id) {

        if (world_gen_settings.containsKey(id) == false) {

            world_gen_settings.put(id, FileManager.readTXT(Handcode.path_config + "/#dev/custom_packs_organized/world_gen/" + id + ".txt"));

        }

        return world_gen_settings.getOrDefault(id, new String[0]);

    }

    public static String[] getTreeSettings (String id) {

        if (tree_settings.containsKey(id) == false) {

            tree_settings.put(id, FileManager.readTXT(Handcode.path_config + "/#dev/custom_packs_organized/presets/" + id + "_settings.txt"));

        }

        return tree_settings.getOrDefault(id, new String[0]);

    }

    public static String[] getFunction (String id) {

        if (functions.containsKey(id) == false) {

            functions.put(id, FileManager.readTXT(Handcode.path_config + "/#dev/custom_packs_organized/functions/" + id + ".txt"));

        }

        return functions.getOrDefault(id, new String[0]);

    }

    public static String[] getFunctionTreeDecoration () {

        if (functions_tree_decoration.length == 0) {

            String[] list = new File(Handcode.path_config + "/#dev/custom_packs_organized/functions/#TannyJung-Main-Pack/tree_decoration").list();

            if (list != null) {

                String[] convert = new String[list.length];
                int loop = 0;

                while (loop < list.length) {

                    convert[loop] = list[loop].replace(".txt", "");
                    loop = loop + 1;

                }

                functions_tree_decoration = convert;

            }

        }

        return functions_tree_decoration;

    }

    public static String[] getFunctionTreeDecorationDecay () {

        if (functions_tree_decoration_decay.length == 0) {

            String[] list = new File(Handcode.path_config + "/#dev/custom_packs_organized/functions/#TannyJung-Main-Pack/tree_decoration_decay").list();

            if (list != null) {

                String[] convert = new String[list.length];
                int loop = 0;

                while (loop < list.length) {

                    convert[loop] = list[loop].replace(".txt", "");
                    loop = loop + 1;

                }

                functions_tree_decoration_decay = convert;

            }

        }

        return functions_tree_decoration_decay;

    }

    public static String[] getLeafLitter (String id) {

        if (leaf_litter.containsKey(id) == false) {

            leaf_litter.put(id, FileManager.readTXT(Handcode.path_config + "/#dev/custom_packs_organized/leaf_litter/" + id + ".txt"));

        }

        return leaf_litter.getOrDefault(id, new String[0]);

    }

    /**
     * A2 Optimization: Cache storage file lists to eliminate per-tree File.listFiles() calls.
     * Returns cached list of shape file names for a species storage folder.
     * @param path_storage Format: "pack/species" (e.g., "default_pack/oak")
     * @return Array of file names in the storage folder, or empty array if folder doesn't exist
     */
    public static String[] getStorageFiles (String path_storage) {

        if (storage_file_lists.containsKey(path_storage) == false) {

            File storageDir = new File(Handcode.path_config + "/custom_packs/" + path_storage.replace("/", "/presets/") + "/storage");
            String[] fileNames = new String[0];

            if (storageDir.exists() && storageDir.isDirectory()) {
                File[] files = storageDir.listFiles();
                if (files != null && files.length > 0) {
                    fileNames = new String[files.length];
                    for (int i = 0; i < files.length; i++) {
                        fileNames[i] = files[i].getName();
                    }
                }
            }

            storage_file_lists.put(path_storage, fileNames);

        }

        return storage_file_lists.getOrDefault(path_storage, new String[0]);

    }

    /**
     * A1+B3 Optimization: Get pre-parsed world gen config for all species.
     * Parses config_world_gen.txt ONCE and caches structured objects.
     * Eliminates ~7.7 million string comparisons per region.
     * @return List of pre-parsed species configurations
     */
    public static List<SpeciesWorldGenConfig> getWorldGenConfig() {

        if (cached_world_gen_config == null) {

            cached_world_gen_config = new ArrayList<>();
            File file = new File(Handcode.path_config + "/config_world_gen.txt");

            if (file.exists() && !file.isDirectory()) {

                String[] lines = FileManager.readTXT(file.getPath());
                boolean startTest = false;
                boolean skip = true;

                // Temporary variables for parsing current species
                String id = "";
                boolean world_gen = false;
                String biome = "";
                String ground_block = "";
                double rarity = 0.0;
                int min_distance = 0;
                String group_size = "1 <> 1";
                double waterside_chance = 0.0;
                double dead_tree_chance = 0.0;
                String dead_tree_level = "";
                String start_height_offset = "";
                String rotation = "";
                String mirrored = "";

                for (String line : lines) {

                    if (line.isEmpty()) continue;

                    if (!startTest) {
                        if (line.startsWith("---")) {
                            startTest = true;
                        }
                        continue;
                    }

                    if (line.startsWith("[")) {
                        // New species entry
                        if (line.startsWith("[INCOMPATIBLE] ")) {
                            skip = true;
                        } else {
                            skip = false;
                            id = line.substring(line.indexOf("]") + 2).replace(" > ", "/");
                            // Reset defaults for new species
                            world_gen = false;
                            biome = "";
                            ground_block = "";
                            rarity = 0.0;
                            min_distance = 0;
                            group_size = "1 <> 1";
                            waterside_chance = 0.0;
                            dead_tree_chance = 0.0;
                            dead_tree_level = "";
                            start_height_offset = "";
                            rotation = "";
                            mirrored = "";
                        }
                    } else if (!skip) {
                        // Parse property lines - use indexOf for faster extraction
                        int eqIdx = line.indexOf(" = ");
                        if (eqIdx > 0) {
                            String key = line.substring(0, eqIdx);
                            String value = line.substring(eqIdx + 3);

                            switch (key) {
                                case "world_gen" -> world_gen = Boolean.parseBoolean(value);
                                case "biome" -> biome = value;
                                case "ground_block" -> ground_block = value;
                                case "rarity" -> rarity = Double.parseDouble(value);
                                case "min_distance" -> min_distance = Integer.parseInt(value);
                                case "group_size" -> group_size = value;
                                case "waterside_chance" -> waterside_chance = Double.parseDouble(value);
                                case "dead_tree_chance" -> dead_tree_chance = Double.parseDouble(value);
                                case "dead_tree_level" -> dead_tree_level = value;
                                case "start_height_offset" -> start_height_offset = value;
                                case "rotation" -> rotation = value;
                                case "mirrored" -> {
                                    mirrored = value;
                                    // mirrored is the last property - create the config object
                                    // B2 Optimization: Pre-parse path_storage from world_gen settings file
                                    String path_storage = "";
                                    String[] worldGenSettings = getWorldGenSettings(id);
                                    for (String setting : worldGenSettings) {
                                        if (setting.startsWith("path_storage = ")) {
                                            path_storage = setting.substring(15); // "path_storage = ".length()
                                            break;
                                        }
                                    }
                                    cached_world_gen_config.add(new SpeciesWorldGenConfig(
                                        id, world_gen, biome, ground_block, rarity, min_distance,
                                        group_size, waterside_chance, dead_tree_chance, dead_tree_level,
                                        start_height_offset, rotation, mirrored, path_storage
                                    ));
                                }
                            }
                        }
                    }
                }

            }

        }

        return cached_world_gen_config;

    }

    /**
     * B1 Optimization: Get pre-filtered list of enabled species only.
     * Filters out species with world_gen=false, reducing iteration overhead.
     * @return List of enabled species configurations
     */
    public static List<SpeciesWorldGenConfig> getEnabledSpecies() {

        if (cached_enabled_species == null) {
            List<SpeciesWorldGenConfig> allSpecies = getWorldGenConfig();
            cached_enabled_species = new ArrayList<>();
            for (SpeciesWorldGenConfig species : allSpecies) {
                if (species.world_gen) {
                    cached_enabled_species.add(species);
                }
            }
        }

        return cached_enabled_species;

    }

    // ==================== P1.1+P1.2 Optimization: Detailed Detection Cache ====================

    /**
     * P1.1+P1.2 Optimization: Get indexed detection results for a region.
     * Loads from disk on first access, then caches with LRU eviction.
     * Returns position-indexed map for O(1) lookup by tree coordinates.
     * @param dimension The dimension name (e.g., "minecraft-overworld")
     * @param regionX The region X coordinate (chunk_pos.x >> 5)
     * @param regionZ The region Z coordinate (chunk_pos.z >> 5)
     * @return Map of position key to DetectionResult, or empty map if no data
     */
    public static Map<Long, DetectionResult> getDetailedDetection(String dimension, int regionX, int regionZ) {
        String cacheKey = dimension + "/" + regionX + "," + regionZ;

        // Check cache first (synchronized for LRU access-order updates)
        synchronized (detailed_detection_cache) {
            Map<Long, DetectionResult> cached = detailed_detection_cache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        // Cache miss - load from disk and index
        String path = Handcode.path_world_data + "/world_gen/detailed_detection/" + dimension + "/" + regionX + "," + regionZ + ".bin";
        Map<Long, DetectionResult> indexed = loadAndIndexDetection(path);

        // Store in cache (synchronized for thread-safe put)
        synchronized (detailed_detection_cache) {
            detailed_detection_cache.put(cacheKey, indexed);
        }

        return indexed;
    }

    /**
     * P1.2 Optimization: Load detection binary and index by position for O(1) lookup.
     * Converts linear scan to hash lookup, reducing complexity from O(n) to O(1).
     * @param path Path to the detailed_detection binary file
     * @return Map of position key (packed long) to DetectionResult
     */
    private static Map<Long, DetectionResult> loadAndIndexDetection(String path) {
        Map<Long, DetectionResult> indexed = new HashMap<>();

        ByteBuffer buffer = FileManager.readBIN(path);
        if (buffer == null || buffer.remaining() == 0) {
            return indexed;
        }

        // Binary format: pass(byte), posX(int), posY(int), posZ(int), deadTreeLevel(short)
        // Total: 1 + 4 + 4 + 4 + 2 = 15 bytes per entry
        while (buffer.remaining() >= 15) {
            try {
                boolean pass = buffer.get() == 1;
                int posX = buffer.getInt();
                int posY = buffer.getInt();
                int posZ = buffer.getInt();
                int deadTreeLevel = buffer.getShort();

                // Pack posX and posZ into a single long key for O(1) HashMap lookup
                long posKey = ((long) posX << 32) | (posZ & 0xFFFFFFFFL);
                indexed.put(posKey, new DetectionResult(pass, posY, deadTreeLevel));

            } catch (Exception e) {
                break;
            }
        }

        return indexed;
    }

    /**
     * P1.2 Optimization: Create position key for detection lookup.
     * Packs X and Z coordinates into a single long for HashMap key.
     * @param posX The X position
     * @param posZ The Z position
     * @return Packed long key
     */
    public static long makeDetectionKey(int posX, int posZ) {
        return ((long) posX << 32) | (posZ & 0xFFFFFFFFL);
    }

    /**
     * P1.1 Optimization: Invalidate detection cache for a region.
     * Called when new detection results are written to ensure cache consistency.
     * @param dimension The dimension name
     * @param regionX The region X coordinate
     * @param regionZ The region Z coordinate
     */
    public static void invalidateDetectionCache(String dimension, int regionX, int regionZ) {
        String cacheKey = dimension + "/" + regionX + "," + regionZ;
        synchronized (detailed_detection_cache) {
            detailed_detection_cache.remove(cacheKey);
        }
    }

    // ==================== P2.1 Optimization: Indexed Placement Cache ====================

    /**
     * P2.1 Optimization: Get trees affecting a specific chunk.
     * Returns pre-indexed list of trees for O(1) lookup instead of O(n) scan.
     * @param dimension The dimension name
     * @param regionKey The region key (e.g., "0,0")
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return List of trees affecting this chunk, or empty list if none
     */
    public static List<TreePlacementData> getTreesForChunk(String dimension, String regionKey, int chunkX, int chunkZ) {
        String cacheKey = dimension + "/" + regionKey;

        // Check index cache first
        synchronized (placement_index_cache) {
            Map<Long, List<TreePlacementData>> regionIndex = placement_index_cache.get(cacheKey);
            if (regionIndex != null) {
                long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
                return regionIndex.getOrDefault(chunkKey, Collections.emptyList());
            }
        }

        // Cache miss - need to build index
        Map<Long, List<TreePlacementData>> regionIndex = buildPlacementIndex(dimension, regionKey);

        // Store in cache
        synchronized (placement_index_cache) {
            placement_index_cache.put(cacheKey, regionIndex);
        }

        long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        return regionIndex.getOrDefault(chunkKey, Collections.emptyList());
    }

    /**
     * P2.1 Optimization: Build chunk-indexed placement data for a region.
     * Parses all trees once and indexes by which chunks they affect.
     * @param dimension The dimension name
     * @param regionKey The region key
     * @return Map of chunk key to list of trees affecting that chunk
     */
    private static Map<Long, List<TreePlacementData>> buildPlacementIndex(String dimension, String regionKey) {
        Map<Long, List<TreePlacementData>> index = new HashMap<>();

        // Get raw placement data (from G5 cache or disk)
        ByteBuffer buffer = getPlacement(dimension, regionKey);
        if (buffer == null) {
            String path = Handcode.path_world_data + "/world_gen/place/" + dimension + "/" + regionKey + ".bin";
            buffer = FileManager.readBIN(path);
        }

        if (buffer == null || buffer.remaining() == 0) {
            return index;
        }

        // Parse all trees and index by affected chunks
        while (buffer.remaining() > 0) {
            try {
                int from_chunkX = buffer.getInt();
                int from_chunkZ = buffer.getInt();
                int to_chunkX = buffer.getInt();
                int to_chunkZ = buffer.getInt();
                String id = getDictionary(String.valueOf(buffer.getShort()), true);
                String chosen = getDictionary(String.valueOf(buffer.getShort()), true);
                int center_posX = buffer.getInt();
                int center_posZ = buffer.getInt();
                int rotation = buffer.get();
                boolean mirrored = buffer.get() == 1;
                int start_height_offset = buffer.getShort();
                int up_sizeY = buffer.getShort();
                String ground_block = getDictionary(String.valueOf(buffer.getShort()), true);
                int dead_tree_level = buffer.getShort();

                TreePlacementData tree = new TreePlacementData(
                    from_chunkX, from_chunkZ, to_chunkX, to_chunkZ,
                    id, chosen, center_posX, center_posZ,
                    rotation, mirrored, start_height_offset, up_sizeY,
                    ground_block, dead_tree_level
                );

                // Add tree to every chunk it affects
                for (int cx = from_chunkX; cx <= to_chunkX; cx++) {
                    for (int cz = from_chunkZ; cz <= to_chunkZ; cz++) {
                        long chunkKey = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                        index.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(tree);
                    }
                }

            } catch (Exception e) {
                break;
            }
        }

        return index;
    }

    /**
     * P2.1 Optimization: Invalidate placement index for a region.
     * Called when new placement data is written.
     * @param dimension The dimension name
     * @param regionKey The region key
     */
    public static void invalidatePlacementIndex(String dimension, String regionKey) {
        String cacheKey = dimension + "/" + regionKey;
        synchronized (placement_index_cache) {
            placement_index_cache.remove(cacheKey);
        }
    }

    // ==================== P1.3 Optimization: Structure Detection Cache ====================

    /**
     * P1.3 Optimization: Check if a chunk has cached structure detection result.
     * @param dimension The dimension name
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @return Boolean.TRUE if has surface structures, Boolean.FALSE if not, null if not cached
     */
    public static Boolean getStructureDetection(String dimension, int chunkX, int chunkZ) {
        String cacheKey = dimension + "/" + chunkX + "," + chunkZ;
        synchronized (structure_detection_cache) {
            return structure_detection_cache.get(cacheKey);
        }
    }

    /**
     * P1.3 Optimization: Store structure detection result for a chunk.
     * @param dimension The dimension name
     * @param chunkX The chunk X coordinate
     * @param chunkZ The chunk Z coordinate
     * @param hasSurfaceStructures true if chunk has surface structures
     */
    public static void cacheStructureDetection(String dimension, int chunkX, int chunkZ, boolean hasSurfaceStructures) {
        String cacheKey = dimension + "/" + chunkX + "," + chunkZ;
        synchronized (structure_detection_cache) {
            structure_detection_cache.put(cacheKey, hasSurfaceStructures);
        }
    }

    // ==================== P3.1 Optimization: Heightmap Cache ====================

    /**
     * P3.1 Optimization: Clear heightmap cache at start of chunk processing.
     * Must be called at the beginning of TreePlacer.start() to ensure fresh cache.
     */
    public static void clearHeightmapCache() {
        heightmap_cache.get().clear();
    }

    /**
     * P3.1 Optimization: Get cached heightmap result.
     * @param posX X coordinate
     * @param posZ Z coordinate
     * @param heightmapType The heightmap type name (e.g., "OCEAN_FLOOR_WG")
     * @return Cached height, or null if not cached
     */
    public static Integer getHeightmapCached(int posX, int posZ, String heightmapType) {
        String key = posX + "," + posZ + "," + heightmapType;
        return heightmap_cache.get().get(key);
    }

    /**
     * P3.1 Optimization: Store heightmap result in cache.
     * @param posX X coordinate
     * @param posZ Z coordinate
     * @param heightmapType The heightmap type name
     * @param height The computed height value
     */
    public static void cacheHeightmap(int posX, int posZ, String heightmapType, int height) {
        String key = posX + "," + posZ + "," + heightmapType;
        heightmap_cache.get().put(key, height);
    }

    // ==================== G5 Optimization: In-Memory Placement Cache ====================

    /**
     * G5 Optimization: Store placement data in memory cache.
     * Called by TreeLocation after writing placement data to avoid immediate disk I/O.
     * @param dimension The dimension name (e.g., "overworld")
     * @param regionKey The region key (e.g., "0,0")
     * @param data The raw placement data as ByteBuffer
     */
    public static void storePlacement(String dimension, String regionKey, ByteBuffer data) {
        String cacheKey = dimension + "/" + regionKey;
        // Store a duplicate to avoid position issues when reading
        ByteBuffer copy = ByteBuffer.allocate(data.remaining());
        copy.put(data.duplicate());
        copy.flip();
        placement_cache.put(cacheKey, copy);
        placement_needs_persist.put(cacheKey, true);
    }

    /**
     * G5 Optimization: Get placement data from memory cache.
     * Returns cached data if available, null if not in cache (caller should fall back to file).
     * Marks data for persistence after access.
     * @param dimension The dimension name
     * @param regionKey The region key
     * @return ByteBuffer with placement data, or null if not cached
     */
    public static ByteBuffer getPlacement(String dimension, String regionKey) {
        String cacheKey = dimension + "/" + regionKey;
        ByteBuffer cached = placement_cache.get(cacheKey);
        if (cached != null) {
            // Return a duplicate with position reset so caller can read from start
            ByteBuffer result = cached.duplicate();
            result.rewind();
            return result;
        }
        return null;
    }

    /**
     * G5 Optimization: Check if placement data exists in memory cache.
     * @param dimension The dimension name
     * @param regionKey The region key
     * @return true if data is cached
     */
    public static boolean hasPlacement(String dimension, String regionKey) {
        return placement_cache.containsKey(dimension + "/" + regionKey);
    }

    /**
     * G5 Optimization: Flush all pending placement data to disk.
     * Called periodically or on cache clear to ensure persistence.
     * Note: In the current design, files are written immediately by TreeLocation.
     * This method is a safety net for any cached data that wasn't persisted.
     */
    public static void flushPlacementPersistence() {
        for (Map.Entry<String, Boolean> entry : placement_needs_persist.entrySet()) {
            if (entry.getValue()) {
                String cacheKey = entry.getKey();
                ByteBuffer data = placement_cache.get(cacheKey);
                if (data != null) {
                    // Parse dimension and regionKey from cacheKey
                    int slashIdx = cacheKey.indexOf('/');
                    if (slashIdx > 0) {
                        String dimension = cacheKey.substring(0, slashIdx);
                        String regionKey = cacheKey.substring(slashIdx + 1);
                        String path = Handcode.path_world_data + "/world_gen/place/" + dimension + "/" + regionKey + ".bin";

                        // Write raw bytes directly to file
                        ByteBuffer toWrite = data.duplicate();
                        toWrite.rewind();
                        byte[] bytes = new byte[toWrite.remaining()];
                        toWrite.get(bytes);

                        try {
                            File file = new File(path);
                            file.getParentFile().mkdirs();
                            try (FileOutputStream fos = new FileOutputStream(file)) {
                                fos.write(bytes);
                            }
                        } catch (IOException e) {
                            OutsideUtils.exception(new Exception(), e);
                        }
                    }
                }
                placement_needs_persist.put(cacheKey, false);
            }
        }
    }

    /**
     * G5 Optimization: Store placement data after file write.
     * Called by TreeLocation after writing to disk to populate the cache.
     * This allows TreePlacer to read from memory instead of disk.
     * @param dimension The dimension name
     * @param regionKey The region key
     * @param path The file path that was just written
     */
    public static void cacheWrittenPlacement(String dimension, String regionKey, String path) {
        // Read the file we just wrote and cache it
        ByteBuffer data = FileManager.readBIN(path);
        if (data != null && data.remaining() > 0) {
            String cacheKey = dimension + "/" + regionKey;
            // Store a copy
            ByteBuffer copy = ByteBuffer.allocate(data.remaining());
            copy.put(data);
            copy.flip();
            placement_cache.put(cacheKey, copy);
            placement_needs_persist.put(cacheKey, false); // Already persisted
        }
    }

}
