package dev.chunkdiff.delta;

import java.io.*;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Un fichier par region (32x32 chunks) : plugins/ChunkDiff/deltas/<monde>/r.<rx>.<rz>.cdf
 * Format : int magic "CDF1", int n, puis n x { int cx, int cz, int len, byte[len] }.
 * Chaque byte[] est un ChunkDelta encode par DeltaCodec.
 * Ecriture atomique (fichier temporaire puis rename).
 */
public final class DeltaStore {
    private static final int MAGIC = 0x43444631; // "CDF1"
    private final Path root;

    public DeltaStore(Path root) { this.root = root; }

    public synchronized Optional<ChunkDelta> load(String world, int cx, int cz) throws IOException {
        Path file = regionFile(world, cx, cz);
        if (!Files.exists(file)) return Optional.empty();
        byte[] blob = readAll(file).get(key(cx, cz));
        return blob == null ? Optional.empty() : Optional.of(DeltaCodec.decode(blob));
    }

    public synchronized void save(String world, ChunkDelta delta) throws IOException {
        Path file = regionFile(world, delta.chunkX(), delta.chunkZ());
        Map<Long, byte[]> entries = Files.exists(file) ? readAll(file) : new LinkedHashMap<>();
        entries.put(key(delta.chunkX(), delta.chunkZ()), DeltaCodec.encode(delta));
        write(file, entries);
    }

    public synchronized void remove(String world, int cx, int cz) throws IOException {
        Path file = regionFile(world, cx, cz);
        if (!Files.exists(file)) return;
        Map<Long, byte[]> entries = readAll(file);
        if (entries.remove(key(cx, cz)) == null) return;
        if (entries.isEmpty()) Files.delete(file); else write(file, entries);
    }

    public Path root() { return root; }

    private Path regionFile(String world, int cx, int cz) {
        int rx = Math.floorDiv(cx, 32), rz = Math.floorDiv(cz, 32);
        return root.resolve(world).resolve("r." + rx + "." + rz + ".cdf");
    }

    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    private static Map<Long, byte[]> readAll(Path file) throws IOException {
        Map<Long, byte[]> map = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("fichier .cdf invalide: " + file);
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                int cx = in.readInt(), cz = in.readInt(), len = in.readInt();
                if (len < 0) throw new IOException("longueur invalide dans " + file);
                byte[] b = new byte[len];
                in.readFully(b);
                map.put(key(cx, cz), b);
            }
        }
        return map;
    }

    private static void write(Path file, Map<Long, byte[]> entries) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(entries.size());
            for (Map.Entry<Long, byte[]> e : entries.entrySet()) {
                long k = e.getKey();
                out.writeInt((int) (k >> 32));
                out.writeInt((int) k);
                out.writeInt(e.getValue().length);
                out.write(e.getValue());
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
