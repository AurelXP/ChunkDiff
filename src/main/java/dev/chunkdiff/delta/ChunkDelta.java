package dev.chunkdiff.delta;

import java.util.List;

/**
 * Difference entre un chunk reel et le chunk que la seed generait.
 * On ne compare QUE : etats de blocs, block entities, entites.
 * On ignore : lumiere, heightmaps, InhabitedTime, LastUpdate (non deterministes).
 */
public record ChunkDelta(int chunkX, int chunkZ, List<BlockChange> blocks,
                         List<byte[]> blockEntitiesNbt, List<byte[]> entitiesNbt) {

    /** Position locale au chunk (x 0-15, y monde, z 0-15) + index de palette. */
    public record BlockChange(byte x, short y, byte z, int paletteIndex) {}

    public boolean isEmpty() {
        return blocks.isEmpty() && blockEntitiesNbt.isEmpty() && entitiesNbt.isEmpty();
    }
}
