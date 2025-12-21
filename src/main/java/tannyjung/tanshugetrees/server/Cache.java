package tannyjung.tanshugetrees.server;

import tannyjung.core.FileManager;
import tannyjung.core.OutsideUtils;
import tannyjung.tanshugetrees.Handcode;

import java.io.File;
import java.nio.ShortBuffer;
import java.util.*;

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

        public SpeciesWorldGenConfig(String id, boolean world_gen, String biome, String ground_block,
                                     double rarity, int min_distance, String group_size,
                                     double waterside_chance, double dead_tree_chance, String dead_tree_level,
                                     String start_height_offset, String rotation, String mirrored) {
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
        }
    }

    private static List<SpeciesWorldGenConfig> cached_world_gen_config = null;
    private static List<SpeciesWorldGenConfig> cached_enabled_species = null;

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

        }

        return Double.parseDouble(String.format("%.2f", size / (1024 * 1024)));

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
                                    cached_world_gen_config.add(new SpeciesWorldGenConfig(
                                        id, world_gen, biome, ground_block, rarity, min_distance,
                                        group_size, waterside_chance, dead_tree_chance, dead_tree_level,
                                        start_height_offset, rotation, mirrored
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

}
