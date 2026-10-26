package dev.chunkdiff.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegionRewriterTest {

    /** Region avec 3 chunks : index 0 (1 secteur 0xAA), index 5 (2 secteurs 0xBB), index 7 (1 secteur 0xCC). */
    private static Path makeRegion(Path dir) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(8192 + 4 * 4096);
        bb.putInt(0, (2 << 8) | 1);        bb.putInt(4096, 111);
        bb.putInt(5 * 4, (3 << 8) | 2);    bb.putInt(4096 + 5 * 4, 222);
        bb.putInt(7 * 4, (5 << 8) | 1);    bb.putInt(4096 + 7 * 4, 333);
        for (int i = 0; i < 4096; i++) bb.put(2 * 4096 + i, (byte) 0xAA);
        for (int i = 0; i < 8192; i++) bb.put(3 * 4096 + i, (byte) 0xBB);
        for (int i = 0; i < 4096; i++) bb.put(5 * 4096 + i, (byte) 0xCC);
        Path f = dir.resolve("r.0.0.mca");
        Files.write(f, bb.array());
        return f;
    }

    @Test
    void dropsAndCompacts(@TempDir Path dir) throws Exception {
        Path f = makeRegion(dir);
        Map<Integer, Integer> drop = new HashMap<>();
        drop.put(5, 222);

        RegionRewriter.Stats dry = RegionRewriter.rewrite(f, drop, false);
        assertEquals(1, dry.dropped());
        assertEquals(8192 + 4 * 4096, Files.size(f)); // simulation : rien ecrit

        RegionRewriter.Stats st = RegionRewriter.rewrite(f, drop, true);
        assertEquals(1, st.dropped());
        assertEquals(8192 + 2 * 4096, Files.size(f));

        ByteBuffer out = ByteBuffer.wrap(Files.readAllBytes(f));
        assertEquals((2 << 8) | 1, out.getInt(0));
        assertEquals(0, out.getInt(5 * 4));
        assertEquals((3 << 8) | 1, out.getInt(7 * 4));
        assertEquals(111, out.getInt(4096));
        assertEquals(333, out.getInt(4096 + 7 * 4));
        assertEquals((byte) 0xAA, out.get(2 * 4096));
        assertEquals((byte) 0xCC, out.get(3 * 4096));
    }

    @Test
    void changedChunkIsKept(@TempDir Path dir) throws Exception {
        Path f = makeRegion(dir);
        long before = Files.size(f);
        Map<Integer, Integer> drop = new HashMap<>();
        drop.put(5, 999); // date differente : le chunk a change depuis le scan

        RegionRewriter.Stats st = RegionRewriter.rewrite(f, drop, true);
        assertEquals(0, st.dropped());
        assertEquals(1, st.skippedChanged());
        assertEquals(before, Files.size(f));
    }

    @Test
    void dropEverythingDeletesFile(@TempDir Path dir) throws Exception {
        Path f = makeRegion(dir);
        Map<Integer, Integer> drop = new HashMap<>();
        drop.put(0, null); drop.put(5, null); drop.put(7, null);
        RegionRewriter.rewrite(f, drop, true);
        assertFalse(Files.exists(f));
    }

    @Test
    void corruptFileIsRejected(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("r.0.0.mca");
        ByteBuffer bb = ByteBuffer.allocate(8192);
        bb.putInt(0, (50 << 8) | 4); // pointe hors du fichier
        Files.write(f, bb.array());
        assertThrows(java.io.IOException.class, () -> RegionRewriter.rewrite(f, Map.of(), true));
    }
}
