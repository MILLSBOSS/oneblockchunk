package com.millsboss.oneblockchunk;

/** Maps pocket ids to chunk coordinates in the pocket world and back. */
public final class PocketGrid {

    /** Chunks between pockets, so trees and caves of one pocket never touch another. */
    public static final int SPACING = 4;
    /** Pockets per grid row. */
    public static final int ROW = 1000;

    private PocketGrid() {
    }

    public static int chunkX(int id) {
        return (id % ROW) * SPACING;
    }

    public static int chunkZ(int id) {
        return (id / ROW) * SPACING;
    }

    /** @return the pocket id owning this chunk, or -1 if it is void. */
    public static int idForChunk(int chunkX, int chunkZ) {
        if (chunkX < 0 || chunkZ < 0) return -1;
        if (chunkX % SPACING != 0 || chunkZ % SPACING != 0) return -1;
        int gx = chunkX / SPACING;
        int gz = chunkZ / SPACING;
        if (gx >= ROW) return -1;
        return gz * ROW + gx;
    }

    /** @return the pocket id owning this block position, or -1. */
    public static int idForBlock(int blockX, int blockZ) {
        return idForChunk(blockX >> 4, blockZ >> 4);
    }
}
