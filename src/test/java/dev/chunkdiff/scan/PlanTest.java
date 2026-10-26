package dev.chunkdiff.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanTest {
    @Test
    void roundTrip(@TempDir Path dir) throws Exception {
        Plan p = new Plan("26.3", 123456789012L, "mon monde", "/srv/mon serveur/world/region",
                List.of(new Plan.Entry(-3, 40, 1700000000), new Plan.Entry(5, -7, 0)));
        Path f = dir.resolve("plans/world.plan");
        Plan.write(f, p);
        assertEquals(p, Plan.read(f));
    }
}
