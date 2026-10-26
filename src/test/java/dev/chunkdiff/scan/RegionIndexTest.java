package dev.chunkdiff.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionIndexTest {

    private static void writeRegion(Path file, int index, int offset, int sectors, int ts) throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(8192);
        bb.putInt(index * 4, (offset << 8) | sectors);
        bb.putInt(4096 + index * 4, ts);
        Files.write(file, bb.array());
    }

    @Test
    void readsHeaders(@TempDir Path dir) throws Exception {
        writeRegion(dir.resolve("r.0.0.mca"), 33, 2, 3, 1700000000);   // x=1, z=1
        writeRegion(dir.resolve("r.-1.0.mca"), 0, 2, 1, 42);           // chunk (-32, 0)
        Files.writeString(dir.resolve("autre.txt"), "ignore");

        List<RegionIndex.Coord> list = RegionIndex.list(dir);
        assertEquals(2, list.size());
        assertEquals(new RegionIndex.Coord(-32, 0, 4096, 42), list.get(0));
        assertEquals(new RegionIndex.Coord(1, 1, 3 * 4096, 1700000000), list.get(1));
    }
}
