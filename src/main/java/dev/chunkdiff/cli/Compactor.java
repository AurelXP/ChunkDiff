package dev.chunkdiff.cli;

import dev.chunkdiff.scan.Plan;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;

/**
 * Applique un plan de compaction. Aucune dependance Bukkit.
 * Appele soit au demarrage du serveur (bootstrapper, AVANT le chargement des mondes),
 * soit en ligne de commande (CompactMain) si l'utilisateur a un acces hors ligne.
 * Le dossier poi n'est volontairement PAS touche.
 */
public final class Compactor {
    public record Summary(int dropped, int skipped, long before, long after) {}

    private Compactor() {}

    public static Summary run(Path planFile, Path regionOverride, boolean apply, boolean checkLock,
                              Consumer<String> log) throws IOException {
        Plan plan = Plan.read(planFile);
        Path regionDir = regionOverride != null ? regionOverride : Path.of(plan.regionDir());
        if (!Files.isDirectory(regionDir)) throw new IOException("dossier region introuvable : " + regionDir);
        Path entitiesDir = regionDir.resolveSibling("entities");

        // nom du fichier region -> (index local -> timestamp attendu)
        Map<String, Map<Integer, Integer>> regions = new TreeMap<>();
        for (Plan.Entry e : plan.chunks()) {
            String name = "r." + Math.floorDiv(e.cx(), 32) + "." + Math.floorDiv(e.cz(), 32) + ".mca";
            int idx = (e.cx() & 31) + (e.cz() & 31) * 32;
            regions.computeIfAbsent(name, k -> new HashMap<>()).put(idx, e.ts());
        }

        log.accept("ChunkDiff plan : monde=" + plan.world() + " mc=" + plan.mc() + " chunks=" + plan.chunks().size());
        log.accept("Dossier region : " + regionDir);
        log.accept(apply ? "MODE APPLICATION" : "MODE SIMULATION (rien n'est modifie)");

        if (apply) {
            if (checkLock) checkServerStopped(regionDir, log);
            backup(regionDir, entitiesDir, regions.keySet(), log);
        }

        long before = 0, after = 0;
        int dropped = 0, skipped = 0;
        for (var e : regions.entrySet()) {
            Path f = regionDir.resolve(e.getKey());
            if (!Files.exists(f)) { log.accept("  absent : " + e.getKey()); continue; }
            RegionRewriter.Stats st = RegionRewriter.rewrite(f, e.getValue(), apply);
            before += st.bytesBefore(); after += st.bytesAfter();
            dropped += st.dropped(); skipped += st.skippedChanged();
            log.accept("  " + e.getKey() + " : -" + st.dropped() + " chunk(s), " + st.skippedChanged()
                    + " garde(s) car modifies depuis le scan, " + st.bytesBefore() / 1024 + " Ko -> " + st.bytesAfter() / 1024 + " Ko");
            Path ef = entitiesDir.resolve(e.getKey());
            if (Files.exists(ef)) {
                // Entites : uniquement pour les chunks reellement retires du dossier region.
                Map<Integer, Integer> sub = new HashMap<>();
                for (int idx : st.droppedIndices()) sub.put(idx, null);
                if (!sub.isEmpty()) RegionRewriter.rewrite(ef, sub, apply);
            }
        }
        log.accept(String.format(Locale.ROOT, "TOTAL : %d chunk(s) retires, %d garde(s) | regions %.1f Mo -> %.1f Mo (gain %.1f Mo)",
                dropped, skipped, before / 1048576.0, after / 1048576.0, (before - after) / 1048576.0));
        return new Summary(dropped, skipped, before, after);
    }

    private static void checkServerStopped(Path regionDir, Consumer<String> log) throws IOException {
        Path dir = regionDir.toAbsolutePath().getParent();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path lock = dir.resolve("session.lock");
            if (!Files.exists(lock)) continue;
            try (FileChannel ch = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                FileLock l = ch.tryLock();
                if (l == null) throw new IOException("le monde semble deja utilise (session.lock verrouille).");
                l.release();
            } catch (IOException e) {
                throw new IOException("impossible de verifier que le monde est libre (" + e.getMessage() + ").");
            }
            return;
        }
        log.accept("ATTENTION : session.lock introuvable, verification impossible.");
    }

    private static void backup(Path regionDir, Path entitiesDir, Set<String> names, Consumer<String> log) throws IOException {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path root = regionDir.toAbsolutePath().getParent().resolve("chunkdiff-backup-" + ts);
        Files.createDirectories(root.resolve("region"));
        Files.createDirectories(root.resolve("entities"));
        for (String n : names) {
            Path f = regionDir.resolve(n);
            if (Files.exists(f)) Files.copy(f, root.resolve("region").resolve(n));
            Path e = entitiesDir.resolve(n);
            if (Files.exists(e)) Files.copy(e, root.resolve("entities").resolve(n));
        }
        log.accept("Sauvegarde creee : " + root);
    }
}
