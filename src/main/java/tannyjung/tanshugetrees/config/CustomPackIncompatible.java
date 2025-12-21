package tannyjung.tanshugetrees.config;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import tannyjung.core.FileManager;
import tannyjung.core.OutsideUtils;
import tannyjung.core.game.Utils;
import tannyjung.tanshugetrees.Handcode;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Files;

public class CustomPackIncompatible {

    public static void scanMain (LevelAccessor level_accessor) {

        // Rename all packs back to normal
        {

            File[] packs = new File(Handcode.path_config + "/custom_packs").listFiles();

            if (packs != null) {

                for (File pack : packs) {

                    rename(pack.getPath(), true);

                }

            }

        }

        File[] packs = new File(Handcode.path_config + "/custom_packs").listFiles();

        if (packs != null) {

            boolean pass = true;

            for (File pack : packs) {

                if (pack.getName().startsWith("[INCOMPATIBLE] ") == false) {

                    // Use info.txt format (matches beta tree pack structure)
                    pass = testInfo(level_accessor, pack + "/info.txt");

                    rename(pack.getPath(), pass);

                }

            }

        }

    }

    public static void scanWorldGenFile () {

        // Tree Settings File
        {

            File file = new File(Handcode.path_config + "/#dev/custom_packs_organized/presets");

            if (file.exists() == true) {

                {

                    try {

                        Files.walk(file.toPath()).forEach(source -> {

                            if (source.toFile().getName().startsWith("[INCOMPATIBLE] ") == false && source.toFile().isDirectory() == false) {

                                if (source.toFile().getName().endsWith("_settings.txt") == true) {

                                    testTreeSettingsFile(source.toFile());

                                }

                            }

                        });

                    } catch (Exception exception) {

                        OutsideUtils.exception(new Exception(), exception);

                    }

                }

            }

        }

        // World Gen File
        {

            File file = new File(Handcode.path_config + "/#dev/custom_packs_organized/world_gen");

            if (file.exists() == true) {

                {

                    try {

                        Files.walk(file.toPath()).forEach(source -> {

                            if (source.toFile().getName().startsWith("[INCOMPATIBLE] ") == false && source.toFile().isDirectory() == false) {

                                testWorldGenFile(source.toFile());

                            }

                        });

                    } catch (Exception exception) {

                        OutsideUtils.exception(new Exception(), exception);

                    }

                }

            }

        }

    }

    private static void rename (String path, boolean pass) {

        File file = new File(path);

        if (pass == true) {

            file.renameTo(new File(file.getParentFile().toPath() + "/" + file.getName().replace("[INCOMPATIBLE] ", "")));

        } else {

            if (file.getName().startsWith("[INCOMPATIBLE] ") == false) {

                file.renameTo(new File(file.getParentFile().toPath() + "/[INCOMPATIBLE] " + file.getName()));

            }

        }

    }

    /**
     * Tests pack compatibility using info.txt format (matches beta tree pack structure).
     * Reads data_structure_version, required_packs, and required_mods from a single info.txt file.
     */
    private static boolean testInfo (LevelAccessor level_accessor, String path) {

        String error = "";
        File file = new File(path);
        String pack_name = file.getParentFile().getName();

        if (file.exists() == true && file.isDirectory() == false) {

            int data_structure_version = 0;
            String required_packs = "none";
            String required_mods = "none";

            // Read info.txt - contains version and dependencies in one file
            {

                try { BufferedReader buffered_reader = new BufferedReader(new FileReader(file), 65536); String read_all = ""; while ((read_all = buffered_reader.readLine()) != null) {

                    {

                        if (read_all.startsWith("data_structure_version = ")) {

                            data_structure_version = Integer.parseInt(read_all.replace("data_structure_version = ", ""));

                        } else if (read_all.startsWith("required_packs = ")) {

                            required_packs = read_all.replace("required_packs = ", "");

                        } else if (read_all.startsWith("required_mods = ")) {

                            required_mods = read_all.replace("required_mods = ", "");

                        }

                    }

                } buffered_reader.close(); } catch (Exception exception) { OutsideUtils.exception(new Exception(), exception); }

            }

            // Test data structure version
            if (data_structure_version != Handcode.DATA_STRUCTURE_VERSION) {

                error = "Detected incompatible pack. Caused by unsupported mod version. [ " + pack_name + " ]";

            }

            // Test required packs
            if (error.equals("") && required_packs.equals("none") == false) {

                for (String test : required_packs.split(", ")) {

                    if (new File(Handcode.path_config + "/custom_packs/" + test).exists() == false) {

                        error = "Detected incompatible pack. Caused by required pack not found. [ " + pack_name + " > " + test + " ]";
                        break;

                    }

                }

            }

            // Test required mods
            if (error.equals("") && required_mods.equals("none") == false) {

                for (String test : required_mods.split(", ")) {

                    if (Utils.misc.isModLoaded(test) == false) {

                        error = "Detected incompatible pack. Caused by required mod not found. [ " + pack_name + " > " + test + " ]";
                        break;

                    }

                }

            }

        } else {

            error = "Detected incompatible pack. Caused by no info file. [ " + pack_name + " ]";

        }

        if (error.equals("") == false) {

            if (level_accessor instanceof ServerLevel level_server) {

                Utils.misc.sendChatMessage(level_server, "@a", "red", "THT : " + error);

            } else {

                Handcode.logger.error(error);

            }

            return false;

        }

        return true;

    }

    private static void testTreeSettingsFile (File file) {

        String error = "";
        String name_pack = file.getParentFile().getParentFile().getName().replace("[INCOMPATIBLE] ", "");
        String name_theme = file.getParentFile().getName();
        String name_tree = file.getName().replace("[INCOMPATIBLE] ", "");

        // Test
        {

            for (String read_all : FileManager.readTXT(file.getPath())) {

                {

                    if (read_all.startsWith("Block ")) {

                        String id = read_all.substring("Block ### #### = ".length());

                        if (id.equals("") == false) {

                            id = id.replace(" keep", "");

                            if (Utils.block.fromText(id).getBlock() == Blocks.AIR) {

                                error = "Detected incompatible tree. Caused by unknown block ID. [ " + name_pack + " > " + name_theme + " > " + name_tree + " > " + id + " ]";
                                break;

                            }

                        }

                    }

                }

            }

        }

        if (error.equals("") == false) {

            Handcode.logger.error(error);

        }

        rename(file.getPath(), error.equals("") == true);

    }

    private static void testWorldGenFile (File file) {

        String error = "";
        String name_pack = file.getParentFile().getParentFile().getName().replace("[INCOMPATIBLE] ", "");
        String name_theme = file.getParentFile().getName();
        String name_tree = file.getName().replace("[INCOMPATIBLE] ", "");
        String path_storage = "";
        String path_tree_settings = "";

        // Read "World Gen" File
        {

            for (String read_all : FileManager.readTXT(file.getPath())) {

                {

                    if (read_all.startsWith("path_storage = ")) {

                        path_storage = read_all.replace("path_storage = ", "");

                    } else if (read_all.startsWith("path_tree_settings = ")) {

                        path_tree_settings = read_all.replace("path_tree_settings = ", "");

                    } else if (read_all.startsWith("path_settings = ")) {

                        // Beta tree pack uses "path_settings" instead of "path_tree_settings"
                        path_tree_settings = read_all.replace("path_settings = ", "");

                    } else {

                        break;

                    }

                }

            }

        }

        // Test Storage
        {

            File file_test = new File(Handcode.path_config + "/custom_packs/" + path_storage.replace("/", "/presets/") + "/storage");

            if (file_test.exists() == true) {

                if (file_test.listFiles() != null && file_test.listFiles().length == 0) {

                    error = "Detected incompatible tree. Caused by empty storage. [ " + name_pack + " > " + name_theme + " > " + name_tree + " ]";

                }

            } else {

                error = "Detected incompatible tree. Caused by storage path not found. [ " + name_pack + " > " + name_theme + " > " + name_tree + " ]";

            }

        }

        // Test Tree Settings
        {

            File file_test = new File(Handcode.path_config + "/#dev/custom_packs_organized/presets/" + path_tree_settings + "_settings.txt");

            if (file_test.exists() == false) {

                error = "Detected incompatible tree. Caused by tree settings not found. [ " + name_pack + " > " + name_theme + " > " + name_tree + " ]";

            }

        }

        if (error.equals("") == false) {

            Handcode.logger.error(error);

        }

        rename(file.getPath(), error.equals("") == true);

    }

}