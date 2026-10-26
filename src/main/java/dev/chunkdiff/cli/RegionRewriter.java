package dev.chunkdiff.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retire des chunks d'un fichier .mca et le reecrit de facon compacte (l'espace est vraiment libere).
 * Aucune dependance Bukkit : testable sans Minecraft.
 */
public final class RegionRewriter {
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    /** @param dropped chunks retires ; @param skippedChanged chunks gardes car modifies depuis le scan */
    public record Stats(int dropped, int skippedChanged, long bytesBefore, long bytesAfter, List<Integer> droppedIndices) {}

    private RegionRewriter() {}

    /**
     * @param drop index local (0-1023) -> timestamp attendu ; valeur null = pas de verification de date
     * @param apply false = simulation (rien n'est ecrit)
     */
    public static Stats rewrite(Path file, Map<Integer, Integer> drop, boolean apply) throws IOException {
        byte[] data = Files.readAllBytes(file);
        if (data.length < 8192 || data.length % 4096 != 0)
            throw new IOException("fichier region invalide ou tronque : " + file);
        ByteBuffer in = ByteBuffer.wrap(data);

        int[] newLoc = new int[1024];
        int[] newTs = new int[1024];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        List<Integer> droppedIdx = new ArrayList<>();
        int nextSector = 2, kept = 0, changed = 0;

        for (int i = 0; i < 1024; i++) {
            int loc = in.getInt(i * 4);
            int ts = in.getInt(4096 + i * 4);
            if (loc == 0) continue;
            int off = loc >>> 8, n = loc & 0xFF;
            if (off < 2 || n == 0 || (long) (off + n) * 4096 > data.length)
                throw new IOException("entree invalide (index " + i + ") dans " + file + " : abandon, rien n'est modifie");
            if (drop.containsKey(i)) {
                Integer expected = drop.get(i);
                if (expected == null || expected == ts) { droppedIdx.add(i); continue; }
                changed++;
            }
            body.write(data, off * 4096, n * 4096);
            newLoc[i] = (nextSector << 8) | n;
            newTs[i] = ts;
            nextSector += n;
            kept++;
        }

        long after = kept == 0 ? 0 : 8192L + body.size();
        Stats stats = new Stats(droppedIdx.size(), changed, data.length, after, List.copyOf(droppedIdx));
        if (!apply || droppedIdx.isEmpty()) return stats;

        if (kept == 0) {
            Files.delete(file);
        } else {
            ByteBuffer header = ByteBuffer.allocate(8192);
            for (int i = 0; i < 1024; i++) {
                header.putInt(i * 4, newLoc[i]);
                header.putInt(4096 + i * 4, newTs[i]);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".chunkdiff-tmp");
            try (var out = Files.newOutputStream(tmp)) {
                out.write(header.array());
                body.writeTo(out);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        // Chunks "geants" stockes a part (c.<cx>.<cz>.mcc) : on retire les orphelins.
        Matcher m = NAME.matcher(file.getFileName().toString());
        if (m.matches()) {
            int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
            for (int i : droppedIdx) {
                int cx = rx * 32 + (i & 31), cz = rz * 32 + (i >> 5);
                Files.deleteIfExists(file.resolveSibling("c." + cx + "." + cz + ".mcc"));
            }
        }
        return stats;
    }
}
