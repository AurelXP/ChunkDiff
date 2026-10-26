package dev.chunkdiff.scan;

import dev.chunkdiff.ChunkDiffPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Compare chaque chunk du monde a sa version "seed pure" generee dans un monde de reference
 * temporaire (meme seed, meme type). MODE LECTURE SEULE : ne supprime rien, produit un rapport.
 *
 * Verdicts :
 *  PRISTINE : identique a la seed -> supprimable
 *  DELTA    : peu de blocs differents -> supprimable en gardant un delta
 *  FULL     : a garder tel quel (trop modifie, block entities, entites importantes...)
 */
public final class PristineScanner {
    public enum Verdict { PRISTINE, DELTA, FULL }

    private record Result(Verdict verdict, int diffs, String reason) {}

    private static final String REF_NAME = "chunkdiff_ref";
    private static final int BYTES_PER_DIFF = 4; // estimation grossiere apres zstd, affinee en phase 4
    private static PristineScanner running;

    private final ChunkDiffPlugin plugin;
    private final World real;
    private final int limit;
    private final CommandSender sender;
    private final double maxFraction;

    private World ref;
    private List<RegionIndex.Coord> coords;
    private int index = 0;
    private volatile boolean cancelled = false;

    private int pristine, delta, full, missing, errors;
    private long totalBytes, saveableBytes;
    private final Map<String, Integer> fullReasons = new TreeMap<>();

    public PristineScanner(ChunkDiffPlugin plugin, World real, int limit, CommandSender sender) {
        this.plugin = plugin;
        this.real = real;
        this.limit = limit;
        this.sender = sender;
        this.maxFraction = plugin.getConfig().getDouble("max-delta-size-fraction", 0.30);
    }

    public static void cancelRunning(CommandSender sender) {
        if (running == null) { sender.sendMessage("Aucun scan en cours."); return; }
        running.cancelled = true;
        sender.sendMessage("Annulation demandee, fin du chunk en cours...");
    }

    public void start() {
        if (running != null) { sender.sendMessage("Un scan est deja en cours."); return; }
        try {
            Path regionDir = RegionIndex.find(real);
            if (regionDir == null) {
                sender.sendMessage("Dossier region introuvable pour le monde " + real.getName());
                return;
            }
            List<RegionIndex.Coord> all = RegionIndex.list(regionDir);
            coords = all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
            sender.sendMessage("Scan (lecture seule) de " + real.getName() + " : " + coords.size() + " chunk(s) sur "
                    + all.size() + ". Dossier : " + regionDir);
        } catch (Exception e) {
            sender.sendMessage("Erreur lecture des regions : " + e);
            return;
        }
        ref = Bukkit.getWorld(REF_NAME);
        if (ref == null) ref = Bukkit.createWorld(new WorldCreator(REF_NAME).copy(real));
        if (ref == null) { sender.sendMessage("Impossible de creer le monde de reference."); return; }
        running = this;
        next();
    }

    private void onMain(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    private void next() {
        if (cancelled || index >= coords.size()) { finish(); return; }
        final RegionIndex.Coord c = coords.get(index++);
        if (index % 100 == 0) sender.sendMessage("Progression : " + index + "/" + coords.size());
        final boolean wasLoaded = real.isChunkLoaded(c.cx(), c.cz());

        real.getChunkAtAsync(c.cx(), c.cz(), false).thenAccept(rc -> {
            if (rc == null) { missing++; onMain(this::next); return; }
            ref.getChunkAtAsync(c.cx(), c.cz(), true).thenAccept(fc -> {
                Gathered g = gather(rc, fc);
                CompletableFuture.supplyAsync(() -> compare(g, c.bytes())).whenComplete((res, ex) -> onMain(() -> {
                    if (ex != null || res == null) {
                        errors++;
                        plugin.getLogger().warning("Erreur chunk " + c.cx() + "," + c.cz() + " : " + ex);
                    } else {
                        record(res, c.bytes());
                    }
                    if (!wasLoaded) real.unloadChunk(c.cx(), c.cz(), false);
                    ref.unloadChunk(c.cx(), c.cz(), false);
                    next();
                }));
            }).exceptionally(ex -> { errors++; onMain(this::next); return null; });
        }).exceptionally(ex -> { errors++; onMain(this::next); return null; });
    }

    // ---- collecte (thread principal) ----

    private record Gathered(ChunkSnapshot a, ChunkSnapshot b, String blocker) {}

    private Gathered gather(Chunk rc, Chunk fc) {
        String blocker = null;
        if (rc.getTileEntities().length > 0) {
            blocker = "block-entities (phase 2b non faite)";
        } else {
            for (Entity e : rc.getEntities()) {
                if (isImportant(e)) { blocker = "entites-importantes"; break; }
            }
        }
        return new Gathered(rc.getChunkSnapshot(false, false, false),
                fc.getChunkSnapshot(false, false, false), blocker);
    }

    /** Entite qu'on ne veut pas perdre : tout sauf les mobs "sauvages" sans nom, non apprivoises. */
    private static boolean isImportant(Entity e) {
        if (e instanceof Player) return false;
        if (e.customName() != null) return true;
        if (e instanceof Villager) return true;
        if (e instanceof Tameable t && t.isTamed()) return true;
        return !(e instanceof Mob);
    }

    // ---- comparaison (thread asynchrone) ----

    private Result compare(Gathered g, int chunkBytes) {
        if (g.blocker() != null) return new Result(Verdict.FULL, 0, g.blocker());
        int minY = real.getMinHeight();
        int sections = (real.getMaxHeight() - minY) / 16;
        long maxDiffs = (long) (maxFraction * chunkBytes / BYTES_PER_DIFF);
        int diffs = 0;
        for (int s = 0; s < sections; s++) {
            if (g.a().isSectionEmpty(s) && g.b().isSectionEmpty(s)) continue;
            int y0 = minY + s * 16;
            for (int y = y0; y < y0 + 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!g.a().getBlockData(x, y, z).equals(g.b().getBlockData(x, y, z))) {
                            diffs++;
                            if (diffs > maxDiffs) return new Result(Verdict.FULL, diffs, "trop-de-differences");
                        }
                    }
                }
            }
        }
        return new Result(diffs == 0 ? Verdict.PRISTINE : Verdict.DELTA, diffs, "");
    }

    private void record(Result r, int chunkBytes) {
        totalBytes += chunkBytes;
        switch (r.verdict()) {
            case PRISTINE -> { pristine++; saveableBytes += chunkBytes; }
            case DELTA -> { delta++; saveableBytes += Math.max(0, chunkBytes - (long) r.diffs() * BYTES_PER_DIFF); }
            case FULL -> { full++; fullReasons.merge(r.reason(), 1, Integer::sum); }
        }
    }

    // ---- fin ----

    private void finish() {
        String name = ref.getName();
        Bukkit.unloadWorld(ref, false);
        deleteRefFolder(name);
        running = null;

        StringBuilder sb = new StringBuilder();
        sb.append(cancelled ? "SCAN ANNULE" : "SCAN TERMINE").append(" - ").append(real.getName()).append('\n');
        sb.append("Chunks analyses : ").append(pristine + delta + full).append(" (absents: ").append(missing)
          .append(", erreurs: ").append(errors).append(")\n");
        sb.append("  identiques a la seed (PRISTINE) : ").append(pristine).append('\n');
        sb.append("  petit diff (DELTA)              : ").append(delta).append('\n');
        sb.append("  a garder (FULL)                 : ").append(full).append('\n');
        for (Map.Entry<String, Integer> e : fullReasons.entrySet())
            sb.append("     - ").append(e.getKey()).append(" : ").append(e.getValue()).append('\n');
        sb.append(String.format(Locale.ROOT, "Taille analysee : %.1f Mo | Gain estime : %.1f Mo (%.0f%%)",
                totalBytes / 1048576.0, saveableBytes / 1048576.0,
                totalBytes == 0 ? 0.0 : 100.0 * saveableBytes / totalBytes));
        String report = sb.toString();
        for (String line : report.split("\n")) sender.sendMessage(line);
        plugin.getLogger().info("\n" + report);
    }

    private void deleteRefFolder(String name) {
        try {
            File container = Bukkit.getWorldContainer().getCanonicalFile();
            File dir = new File(container, name).getCanonicalFile();
            if (!dir.getName().equals(REF_NAME) || !dir.getParentFile().equals(container) || !dir.exists()) return;
            try (Stream<Path> s = Files.walk(dir.toPath())) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Impossible de supprimer le monde de reference : " + e);
        }
    }
}
