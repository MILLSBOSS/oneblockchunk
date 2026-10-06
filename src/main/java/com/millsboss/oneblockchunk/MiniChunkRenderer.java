package com.millsboss.oneblockchunk;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Draws a 1/16 scale copy of each pocket chunk on top of its OneBlock Chunk using
 * block display entities. Only blocks with a visible face are drawn, and refreshes
 * only touch blocks that changed, so live edits inside the pocket show up cheaply.
 */
public final class MiniChunkRenderer {

    private static final float SCALE = 1f / 16f;
    /** Most layers of the pocket shown above the block (counted down from the top). */
    private static final int MAX_LAYERS = 48;
    /** Layers of ground shown below the lowest surface point. */
    private static final int GROUND_LAYERS = 3;
    private static final int MAX_DISPLAYS = 3000;

    private static final BlockData WATER_STANDIN = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
    private static final BlockData LAVA_STANDIN = Material.ORANGE_STAINED_GLASS.createBlockData();

    private final OneBlockChunkPlugin plugin;

    private static final class Model {
        int minY = Integer.MIN_VALUE;
        final Map<Long, BlockDisplay> displays = new HashMap<>();
        final Map<Long, BlockData> shown = new HashMap<>();
    }

    private final Map<Integer, Model> models = new HashMap<>();
    private final Set<Integer> dirty = new HashSet<>();
    private final Set<Integer> rendering = new HashSet<>();

    public MiniChunkRenderer(OneBlockChunkPlugin plugin) {
        this.plugin = plugin;
    }

    public void markDirty(int id) {
        dirty.add(id);
    }

    /** Called on a timer: redraw any placed pockets that changed. */
    public void flushDirty() {
        if (dirty.isEmpty()) return;
        Iterator<Integer> it = dirty.iterator();
        while (it.hasNext()) {
            int id = it.next();
            PocketStore.BlockKey key = plugin.store().placement(id);
            if (key == null) {
                it.remove();
                continue;
            }
            Block b = key.block();
            if (b == null || !b.getWorld().isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
                it.remove();
                continue;
            }
            it.remove();
            render(id);
        }
    }

    public void render(int id) {
        PocketStore.BlockKey key = plugin.store().placement(id);
        if (key == null || !rendering.add(id)) return;
        World pocketWorld = plugin.pocketWorld();
        pocketWorld.getChunkAtAsync(PocketGrid.chunkX(id), PocketGrid.chunkZ(id)).whenComplete((chunk, err) -> {
            rendering.remove(id);
            if (err != null || chunk == null) return;
            // still placed in the same spot?
            if (!key.equals(plugin.store().placement(id))) return;
            Block base = key.block();
            if (base == null || !base.getWorld().isChunkLoaded(key.x() >> 4, key.z() >> 4)) return;
            try {
                apply(id, base, chunk.getChunkSnapshot(false, false, false), pocketWorld.getMinHeight(),
                        pocketWorld.getMaxHeight());
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not draw OneBlock Chunk #" + id, ex);
            }
        });
    }

    private void apply(int id, Block base, ChunkSnapshot snap, int worldMin, int worldMax) {
        // 1. find the vertical window to show
        int maxTop = worldMin;
        int minTop = worldMax;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = worldMin - 1;
                for (int y = worldMax - 1; y >= worldMin; y--) {
                    if (!snap.getBlockType(x, y, z).isAir()) {
                        top = y;
                        break;
                    }
                }
                if (top < worldMin) continue;
                maxTop = Math.max(maxTop, top);
                minTop = Math.min(minTop, top);
            }
        }
        if (minTop > maxTop) { // completely empty pocket
            minTop = maxTop = worldMin;
        }
        int minY = Math.max(worldMin, Math.max(minTop - GROUND_LAYERS, maxTop - MAX_LAYERS + 1));

        // 2. collect visible blocks, top down, until the cap
        Map<Long, BlockData> want = new HashMap<>();
        outer:
        for (int y = maxTop; y >= minY; y--) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    Material type = snap.getBlockType(x, y, z);
                    if (type.isAir() || type == Material.LIGHT || type == Material.BARRIER) continue;
                    if (!visible(snap, x, y, z, minY, maxTop)) continue;
                    BlockData data = switch (type) {
                        case WATER, BUBBLE_COLUMN -> WATER_STANDIN;
                        case LAVA -> LAVA_STANDIN;
                        default -> snap.getBlockData(x, y, z);
                    };
                    want.put(pack(x, y, z), data);
                    if (want.size() >= MAX_DISPLAYS) break outer;
                }
            }
        }

        // 3. diff against what is shown
        Model model = models.computeIfAbsent(id, k -> new Model());
        if (model.minY != minY) {
            clearModel(model);
            model.minY = minY;
        }
        Location origin = base.getLocation().add(0, 1, 0);
        Iterator<Map.Entry<Long, BlockDisplay>> it = model.displays.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, BlockDisplay> e = it.next();
            BlockData target = want.get(e.getKey());
            BlockDisplay d = e.getValue();
            if (target == null || !d.isValid()) {
                if (d.isValid()) d.remove();
                it.remove();
                model.shown.remove(e.getKey());
            } else if (!target.equals(model.shown.get(e.getKey()))) {
                d.setBlock(target);
                model.shown.put(e.getKey(), target);
            }
        }
        World world = base.getWorld();
        for (Map.Entry<Long, BlockData> e : want.entrySet()) {
            if (model.displays.containsKey(e.getKey())) continue;
            long p = e.getKey();
            int x = unpackX(p), y = unpackY(p), z = unpackZ(p);
            Location loc = origin.clone().add(x * SCALE, (y - minY) * SCALE, z * SCALE);
            BlockData data = e.getValue();
            BlockDisplay d = world.spawn(loc, BlockDisplay.class, disp -> {
                disp.setPersistent(false);
                disp.setBlock(data);
                disp.setViewRange(0.6f);
                disp.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(SCALE, SCALE, SCALE), new AxisAngle4f()));
                disp.addScoreboardTag("oneblockchunk_mini");
            });
            model.displays.put(p, d);
            model.shown.put(p, data);
        }
    }

    private static boolean visible(ChunkSnapshot s, int x, int y, int z, int minY, int maxY) {
        if (y == maxY || x == 0 || x == 15 || z == 0 || z == 15) return true;
        return open(s, x + 1, y, z) || open(s, x - 1, y, z)
                || open(s, x, y + 1, z) || (y > minY && open(s, x, y - 1, z))
                || open(s, x, y, z + 1) || open(s, x, y, z - 1);
    }

    private static boolean open(ChunkSnapshot s, int x, int y, int z) {
        return !s.getBlockType(x, y, z).isOccluding();
    }

    public void remove(int id) {
        Model m = models.remove(id);
        if (m != null) clearModel(m);
    }

    /** The overworld chunk unloaded: its non-persistent displays are gone, forget them. */
    public void forget(int id) {
        Model m = models.remove(id);
        if (m != null) clearModel(m);
    }

    public void removeAll() {
        for (Model m : models.values()) clearModel(m);
        models.clear();
        dirty.clear();
    }

    private static void clearModel(Model m) {
        for (BlockDisplay d : m.displays.values()) {
            if (d.isValid()) d.remove();
        }
        m.displays.clear();
        m.shown.clear();
    }

    /** Remove stray mini displays left over (e.g. after a crash) near a block. */
    public static void sweepStrays(World world) {
        for (BlockDisplay d : world.getEntitiesByClass(BlockDisplay.class)) {
            if (d.getScoreboardTags().contains("oneblockchunk_mini")) d.remove();
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) (y + 4096) << 8) | (x << 4) | z;
    }

    private static int unpackX(long p) {
        return (int) ((p >> 4) & 15);
    }

    private static int unpackZ(long p) {
        return (int) (p & 15);
    }

    private static int unpackY(long p) {
        return (int) (p >> 8) - 4096;
    }
}
