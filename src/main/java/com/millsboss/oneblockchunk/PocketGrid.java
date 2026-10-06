package com.millsboss.oneblockchunk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps pocket ids to chunk coordinates in the pocket world and back. The generator
 * reads this from worker threads, so the lookups are concurrent maps.
 *
 * <p>New pockets are spread far apart (so nothing from another pocket is ever in view
 * and there is room for the giant view of the outside around each one). Pockets made
 * by version 1.0 used a tighter legacy spacing and keep their original chunks.
 */
public final class PocketGrid {

    /** Chunks between new pockets (1024 blocks). */
    public static final int SPACING = 64;
    /** Spacing used by version 1.0, kept so old pockets keep their builds. */
    public static final int LEGACY_SPACING = 4;
    public static final int ROW = 1000;

    private static final Map<Integer, long[]> CHUNK_BY_ID = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> ID_BY_CHUNK = new ConcurrentHashMap<>();

    private PocketGrid() {
    }

    static void clear() {
        CHUNK_BY_ID.clear();
        ID_BY_CHUNK.clear();
    }

    static void register(int id, int chunkX, int chunkZ) {
        CHUNK_BY_ID.put(id, new long[]{chunkX, chunkZ});
        ID_BY_CHUNK.put(Chunks.key(chunkX, chunkZ), id);
    }

    /** Where a version 1.0 pocket lives. */
    static int[] legacySlot(int id) {
        return new int[]{(id % ROW) * LEGACY_SPACING, (id / ROW) * LEGACY_SPACING};
    }

    /** Where the n-th new pocket lives (negative quadrant, clear of legacy pockets). */
    static int[] newSlot(int slot) {
        return new int[]{-SPACING * (1 + slot % ROW), -SPACING * (1 + slot / ROW)};
    }

    public static boolean known(int id) {
        return CHUNK_BY_ID.containsKey(id);
    }

    public static int chunkX(int id) {
        long[] c = CHUNK_BY_ID.get(id);
        return c == null ? 0 : (int) c[0];
    }

    public static int chunkZ(int id) {
        long[] c = CHUNK_BY_ID.get(id);
        return c == null ? 0 : (int) c[1];
    }

    public static int minX(int id) {
        return chunkX(id) << 4;
    }

    public static int minZ(int id) {
        return chunkZ(id) << 4;
    }

    /** @return the pocket id owning this chunk, or -1 if it is void. */
    public static int idForChunk(int chunkX, int chunkZ) {
        Integer id = ID_BY_CHUNK.get(Chunks.key(chunkX, chunkZ));
        return id == null ? -1 : id;
    }

    /** @return the pocket id owning this block position, or -1. */
    public static int idForBlock(int blockX, int blockZ) {
        return idForChunk(blockX >> 4, blockZ >> 4);
    }
}
