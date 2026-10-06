package com.millsboss.oneblockchunk;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Generator for the pocket world. Every OneBlock Chunk owns exactly one chunk on a
 * spaced grid. Those chunks get full vanilla terrain (noise, surface, caves, trees,
 * ores, mobs); every other chunk stays empty void, so each pocket is an island chunk.
 */
public final class PocketGenerator extends ChunkGenerator {

    private static boolean isPocket(int chunkX, int chunkZ) {
        return PocketGrid.idForChunk(chunkX, chunkZ) >= 0;
    }

    @Override
    public boolean shouldGenerateNoise(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return isPocket(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateSurface(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return isPocket(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateCaves(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return isPocket(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateDecorations(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return isPocket(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateMobs(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return isPocket(chunkX, chunkZ);
    }

    @Override
    public boolean shouldGenerateStructures(WorldInfo info, Random random, int chunkX, int chunkZ) {
        return false;
    }

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 8.5, 100, 8.5);
    }
}
