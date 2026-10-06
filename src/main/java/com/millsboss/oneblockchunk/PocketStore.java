package com.millsboss.oneblockchunk;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Remembers which pocket ids exist and where each OneBlock Chunk is currently placed.
 * The pocket itself (the real chunk) lives in the pocket world and is never deleted,
 * so breaking and re-placing the block keeps everything the player built.
 */
public final class PocketStore {

    /** Block position key, independent of Location equality quirks. */
    public record BlockKey(UUID world, int x, int y, int z) {
        public static BlockKey of(Block b) {
            return new BlockKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }

        public Block block() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : w.getBlockAt(x, y, z);
        }

        public long chunkKey() {
            return Chunks.key(x >> 4, z >> 4);
        }
    }

    private final File file;
    private int nextId = 0;
    private final Map<Integer, BlockKey> placedById = new HashMap<>();
    private final Map<BlockKey, Integer> idByBlock = new HashMap<>();

    public PocketStore(File dataFolder) {
        this.file = new File(dataFolder, "pockets.yml");
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = yml.getInt("next-id", 0);
        ConfigurationSection placed = yml.getConfigurationSection("placed");
        if (placed == null) return;
        for (String k : placed.getKeys(false)) {
            ConfigurationSection s = placed.getConfigurationSection(k);
            if (s == null) continue;
            try {
                int id = Integer.parseInt(k);
                BlockKey key = new BlockKey(UUID.fromString(Objects.requireNonNull(s.getString("world"))),
                        s.getInt("x"), s.getInt("y"), s.getInt("z"));
                placedById.put(id, key);
                idByBlock.put(key, id);
            } catch (RuntimeException ignored) {
                // skip corrupt entry
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (Map.Entry<Integer, BlockKey> e : placedById.entrySet()) {
            String p = "placed." + e.getKey();
            BlockKey k = e.getValue();
            yml.set(p + ".world", k.world().toString());
            yml.set(p + ".x", k.x());
            yml.set(p + ".y", k.y());
            yml.set(p + ".z", k.z());
        }
        try {
            file.getParentFile().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            Bukkit.getLogger().warning("[OneBlockChunk] Could not save pockets.yml: " + ex.getMessage());
        }
    }

    public int newId() {
        int id = nextId++;
        save();
        return id;
    }

    public boolean isKnownId(int id) {
        return id >= 0 && id < nextId;
    }

    public Integer idAt(Block block) {
        return idByBlock.get(BlockKey.of(block));
    }

    public BlockKey placement(int id) {
        return placedById.get(id);
    }

    public Map<Integer, BlockKey> allPlaced() {
        return placedById;
    }

    public void place(int id, Block block) {
        BlockKey key = BlockKey.of(block);
        placedById.put(id, key);
        idByBlock.put(key, id);
        save();
    }

    public void remove(int id) {
        BlockKey key = placedById.remove(id);
        if (key != null) idByBlock.remove(key);
        save();
    }

    public Collection<Integer> idsInChunk(UUID world, long chunkKey) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (Map.Entry<Integer, BlockKey> e : placedById.entrySet()) {
            BlockKey k = e.getValue();
            if (k.world().equals(world) && k.chunkKey() == chunkKey) out.add(e.getKey());
        }
        return out;
    }

    public static Location center(BlockKey k) {
        World w = Bukkit.getWorld(k.world());
        return w == null ? null : new Location(w, k.x() + 0.5, k.y(), k.z() + 0.5);
    }
}
