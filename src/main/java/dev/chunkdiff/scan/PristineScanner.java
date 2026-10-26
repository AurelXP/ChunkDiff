package dev.chunkdiff.scan;

import dev.chunkdiff.ChunkDiffPlugin;
import dev.chunkdiff.delta.ChunkDelta;
import dev.chunkdiff.delta.DeltaStore;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Compare chaque chunk du monde a sa version "seed pure" generee dans un monde de reference
 * temporaire (meme seed, meme type).
 *
 *  scan : LECTURE SEULE, produit un rapport.
 *  plan : comme scan, mais ecrit aussi les deltas (plugins/ChunkDiff/deltas) et un fichier plan
 *         (plugins/ChunkDiff/plans/<monde>.plan). Ne supprime RIEN : la suppression se fait hors ligne
 *         avec l'outil "compact" (serveur arrete).
 *
 * Verdicts : PRISTINE (identique a la seed), DELTA (peu de blocs differents), FULL (a garder).
 */
public final class PristineScanner {
    public enum Verdict { PRISTINE, DELTA, FULL }

    private record Result(Verdict verdict, int diffs, String reason) {}

    private static final String REF_NAME = "chunkdiff_ref";
    private static final int BYTES_PER_DIFF = 4; // estimation grossiere apres zstd
    private static PristineScanner running;

    private final ChunkDiffPlugin plugin;
    private final World real;
    private final int limit;
    private final CommandSender sender;
    private final boolean writePlan;
    private final double maxFraction;
    private final long minAgeSeconds;
    private final DeltaStore store;

    private World ref;
    private Path regionDir;
    private List<RegionIndex.Coord> coords;
    private int index = 0;
    private volatile boolean cancelled = false;

    private int pristine, delta, full, missing, errors;
    private long totalBytes, saveableBytes;
    private final Map<String, Integer> fullReasons = new TreeMap<>();
    private final List<Plan.Entry> planEntries = new ArrayList<>();

    public PristineScanner(ChunkDiffPlugin plugin, World real, int limit, CommandSender sender, boolean writePlan) {
        this.plugin = plugin;
        this.real = real;
        this.limit = limit;
        this.sender = sender;
        this.writePlan = writePlan;
        this.store = plugin.store();
        this.maxFraction = plugin.getConfig().getDouble("max-delta-size-fraction", 0.30);
        this.minAgeSeconds = (long) (plugin.getConfig().getDouble("min-age-days", 7) * 86400);
    }

    public static void cancelRunning(CommandSender sender) {
        if (running == null) { sender.sendMessage("Aucun scan en cours."); return; }
        running.cancelled = true;
        sender.sendMessage("Annulation demandee, fin du chunk en cours...");
    }

    public void start() {
        if (running != null) { sender.sendMessage("Un scan est deja en cours."); return; }
        try {
            regionDir = RegionIndex.find(real);
            if (regionDir == null) {
                sender.sendMessage("Dossier region introuvable pour le monde " + real.getName());
                return;
            }
            List<RegionIndex.Coord> all = RegionIndex.list(regionDir);
            coords = all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
            sender.sendMessage((writePlan ? "PLAN" : "Scan (lecture seule)") + " de " + real.getName() + " : "
                    + coords.size() + " chunk(s) sur " + all.size() + ". Dossier : " + regionDir);
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

        // Chunk modifie recemment : on n'y touche pas.
        long now = System.currentTimeMillis() / 1000;
        if (minAgeSeconds > 0 && c.timestamp() > 0 && c.timestamp() > now - minAgeSeconds) {
            full++;
            fullReasons.merge("modifie-recemment", 1, Integer::sum);
            onMain(this::next);
            return;
        }

        final boolean wasLoaded = real.isChunkLoaded(c.cx(), c.cz());
        real.getChunkAtAsync(c.cx(), c.cz(), false).thenAccept(rc -> {
            if (rc == null) { missing++; onMain(this::next); return; }
            ref.getChunkAtAsync(c.cx(), c.cz(), true).thenAccept(fc -> {
                Gathered g = gather(rc, fc);
                CompletableFuture.supplyAsync(() -> compare(g, c)).whenComplete((res, ex) -> onMain(() -> {
                    if (ex != null || res == null) {
                        errors++;
                        plugin.getLogger().warning("Erreur chunk " + c.cx() + "," + c.cz() + " : " + ex);
                    } else {
                        record(res, c);
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

    private Result compare(Gathered g, RegionIndex.Coord c) {
        if (g.blocker() != null) return new Result(Verdict.FULL, 0, g.blocker());
        int minY = real.getMinHeight();
        int sections = (real.getMaxHeight() - minY) / 16;
        long maxDiffs = (long) (maxFraction * c.bytes() / BYTES_PER_DIFF);
        int diffs = 0;

        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIdx = new HashMap<>();
        List<ChunkDelta.BlockChange> changes = new ArrayList<>();

        for (int s = 0; s < sections; s++) {
            if (g.a().isSectionEmpty(s) && g.b().isSectionEmpty(s)) continue;
            int y0 = minY + s * 16;
            for (int y = y0; y < y0 + 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        var actual = g.a().getBlockData(x, y, z);
                        if (actual.equals(g.b().getBlockData(x, y, z))) continue;
                        diffs++;
                        if (diffs > maxDiffs) return new Result(Verdict.FULL, diffs, "trop-de-differences");
                        if (writePlan) {
                            String str = actual.getAsString();
                            Integer idx = paletteIdx.get(str);
                            if (idx == null) { idx = palette.size(); palette.add(str); paletteIdx.put(str, idx); }
                            changes.add(new ChunkDelta.BlockChange((byte) x, (short) y, (byte) z, idx));
                        }
                    }
                }
            }
        }
        if (diffs == 0) return new Result(Verdict.PRISTINE, 0, "");
        if (writePlan) {
            try {
                store.save(real.getName(), new ChunkDelta(c.cx(), c.cz(), palette, changes, List.of(), List.of()));
            } catch (Exception e) {
                return new Result(Verdict.FULL, diffs, "echec-ecriture-delta");
            }
        }
        return new Result(Verdict.DELTA, diffs, "");
    }

    private void record(Result r, RegionIndex.Coord c) {
        totalBytes += c.bytes();
        switch (r.verdict()) {
            case PRISTINE -> { pristine++; saveableBytes += c.bytes(); }
            case DELTA -> { delta++; saveableBytes += Math.max(0, c.bytes() - (long) r.diffs() * BYTES_PER_DIFF); }
            case FULL -> { full++; fullReasons.merge(r.reason(), 1, Integer::sum); }
        }
        if (writePlan && r.verdict() != Verdict.FULL) planEntries.add(new Plan.Entry(c.cx(), c.cz(), c.timestamp()));
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

        if (writePlan) {
            try {
                String mc = Bukkit.getMinecraftVersion();
                Path planFile = plugin.getDataPath().resolve("plans").resolve(real.getName() + ".plan");
                Plan.write(planFile, new Plan(mc, real.getSeed(), real.getName(),
                        regionDir.toAbsolutePath().toString(), planEntries));
                Files.createDirectories(store.root());
                Files.writeString(store.root().resolve("mc-version.txt"), mc);
                sb.append("\nPlan ecrit : ").append(planFile.toAbsolutePath()).append(" (").append(planEntries.size())
                  .append(" chunk(s)).\nNE METTEZ PLUS A JOUR minecraft tant que ces chunks sont supprimes.")
                  .append("\nPour appliquer : /chunkdiff compact (lire les consignes), puis /chunkdiff compact confirm et redemarrez le serveur.");
            } catch (Exception e) {
                sb.append("\nECHEC ecriture du plan : ").append(e);
            }
        }
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
