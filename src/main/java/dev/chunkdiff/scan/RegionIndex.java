package dev.chunkdiff.scan;

import org.bukkit.World;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Lit les en-tetes des fichiers .mca pour savoir quels chunks existent sur disque et leur taille. */
public final class RegionIndex {
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /** @param bytes taille occupee sur disque (secteurs de 4096 octets) */
    public record Coord(int cx, int cz, int bytes) {}

    private RegionIndex() {}

    /** Trouve le dossier "region" de la dimension du monde (heuristique, a verifier dans le rapport). */
    public static Path find(World world) throws IOException {
        Path base = world.getWorldFolder().toPath();
        List<Path> candidates;
        try (Stream<Path> s = Files.walk(base, 6)) {
            candidates = s.filter(p -> p.getFileName() != null
                    && p.getFileName().toString().equals("region") && Files.isDirectory(p)).toList();
        }
        List<Path> matching = new ArrayList<>();
        for (Path p : candidates) {
            String rel = base.relativize(p).toString().toLowerCase(Locale.ROOT);
            boolean nether = rel.contains("dim-1") || rel.contains("nether");
            boolean end = rel.contains("dim1") || rel.contains("the_end");
            boolean ok = switch (world.getEnvironment()) {
                case NETHER -> nether;
                case THE_END -> end;
                default -> !nether && !end;
            };
            if (ok) matching.add(p);
        }
        if (matching.isEmpty() && candidates.size() == 1) matching.addAll(candidates);
        matching.sort(Comparator.comparingInt(p -> p.toString().length()));
        return matching.isEmpty() ? null : matching.get(0);
    }

    /** Liste tous les chunks presents dans les .mca du dossier, tries par (cx, cz). */
    public static List<Coord> list(Path regionDir) throws IOException {
        List<Coord> res = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(regionDir)) {
            files = s.filter(p -> NAME.matcher(p.getFileName().toString()).matches()).toList();
        }
        for (Path f : files) {
            Matcher m = NAME.matcher(f.getFileName().toString());
            if (!m.matches()) continue;
            int rx = Integer.parseInt(m.group(1));
            int rz = Integer.parseInt(m.group(2));
            byte[] header;
            try (InputStream in = Files.newInputStream(f)) {
                header = in.readNBytes(4096);
            }
            if (header.length < 4096) continue;
            ByteBuffer bb = ByteBuffer.wrap(header);
            for (int i = 0; i < 1024; i++) {
                int v = bb.getInt(i * 4);
                int sectors = v & 0xFF;
                if (v == 0 || sectors == 0) continue;
                res.add(new Coord(rx * 32 + (i & 31), rz * 32 + (i >> 5), sectors * 4096));
            }
        }
        res.sort(Comparator.comparingInt(Coord::cx).thenComparingInt(Coord::cz));
        return res;
    }
}
