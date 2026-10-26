package dev.chunkdiff.delta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeltaStoreTest {

    private static ChunkDelta sample(int cx, int cz) {
        return new ChunkDelta(cx, cz,
                List.of("minecraft:stone", "minecraft:chest[facing=north]"),
                List.of(new ChunkDelta.BlockChange((byte) 3, (short) -60, (byte) 15, 0),
                        new ChunkDelta.BlockChange((byte) 0, (short) 319, (byte) 7, 1)),
                List.of(new byte[]{1, 2, 3}),
                List.of());
    }

    @Test
    void codecRoundTrip() throws Exception {
        ChunkDelta in = sample(-5, 40);
        ChunkDelta out = DeltaCodec.decode(DeltaCodec.encode(in));
        assertEquals(in.chunkX(), out.chunkX());
        assertEquals(in.chunkZ(), out.chunkZ());
        assertEquals(in.palette(), out.palette());
        assertEquals(in.blocks(), out.blocks());
        assertEquals(1, out.blockEntitiesNbt().size());
        assertArrayEquals(new byte[]{1, 2, 3}, out.blockEntitiesNbt().get(0));
        assertTrue(out.entitiesNbt().isEmpty());
    }

    @Test
    void storeSaveLoadRemove(@TempDir Path dir) throws Exception {
        DeltaStore store = new DeltaStore(dir);
        assertTrue(store.load("world", 1, 1).isEmpty());

        store.save("world", sample(1, 1));
        store.save("world", sample(-1, -33)); // autre region
        store.save("world", sample(2, 2));    // meme region que (1,1)

        assertEquals(1, store.load("world", 1, 1).orElseThrow().chunkX());
        assertEquals(-33, store.load("world", -1, -33).orElseThrow().chunkZ());
        assertEquals(2, store.load("world", 2, 2).orElseThrow().chunkX());

        store.remove("world", 1, 1);
        assertTrue(store.load("world", 1, 1).isEmpty());
        assertTrue(store.load("world", 2, 2).isPresent());
    }
}
