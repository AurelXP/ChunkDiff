package dev.chunkdiff.debug;

import dev.chunkdiff.ChunkDiffPlugin;
import dev.chunkdiff.delta.ChunkDelta;
import dev.chunkdiff.delta.DeltaStore;
import dev.chunkdiff.scan.PristineScanner;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * /chunkdiff testdelta           : delta de test dans le chunk (10000,10000)
 * /chunkdiff scan [monde] [n|all] : scan lecture seule (n chunks max, defaut 500)
 * /chunkdiff plan [monde] [n|all] : comme scan + ecrit les deltas et le plan (ne supprime rien)
 * /chunkdiff compact [confirm|cancel] : programme l'application du plan au PROCHAIN demarrage
 * /chunkdiff cancel              : annule le scan en cours
 */
public final class DebugCommand extends Command {
    public static final int TEST_CHUNK_X = 10000;
    public static final int TEST_CHUNK_Z = 10000;

    private final ChunkDiffPlugin plugin;
    private final DeltaStore store;

    public DebugCommand(ChunkDiffPlugin plugin, DeltaStore store) {
        super("chunkdiff");
        this.plugin = plugin;
        this.store = store;
        setDescription("ChunkDiff");
        setUsage("/chunkdiff <testdelta|scan|plan|compact|cancel>");
        setPermission("chunkdiff.admin");
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("testdelta")) {
            testDelta(sender);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("compact")) {
            compact(sender, args.length >= 2 ? args[1] : "");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("cancel")) {
            PristineScanner.cancelRunning(sender);
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("scan") || args[0].equalsIgnoreCase("plan"))) {
            boolean writePlan = args[0].equalsIgnoreCase("plan");
            World w = args.length >= 2 ? plugin.getServer().getWorld(args[1]) : plugin.getServer().getWorlds().get(0);
            if (w == null) { sender.sendMessage("Monde inconnu : " + args[1]); return true; }
            int limit = 500;
            if (args.length >= 3) {
                if (args[2].equalsIgnoreCase("all")) limit = Integer.MAX_VALUE;
                else {
                    try { limit = Math.max(1, Integer.parseInt(args[2])); }
                    catch (NumberFormatException e) { sender.sendMessage("Nombre invalide : " + args[2]); return true; }
                }
            }
            new PristineScanner(plugin, w, limit, sender, writePlan).start();
            return true;
        }
        sender.sendMessage("Usage : /chunkdiff <testdelta|scan|plan [monde] [n|all]|compact [confirm|cancel]|cancel>");
        return true;
    }

    private void testDelta(CommandSender sender) {
        World world = plugin.getServer().getWorlds().get(0);
        ChunkDelta delta = new ChunkDelta(TEST_CHUNK_X, TEST_CHUNK_Z,
                List.of("minecraft:diamond_block"),
                List.of(new ChunkDelta.BlockChange((byte) 0, (short) 300, (byte) 0, 0),
                        new ChunkDelta.BlockChange((byte) 1, (short) 300, (byte) 0, 0),
                        new ChunkDelta.BlockChange((byte) 2, (short) 300, (byte) 0, 0)),
                List.of(), List.of());
        try {
            store.save(world.getName(), delta);
            sender.sendMessage("Delta de test enregistre pour " + world.getName()
                    + ". Teleportez-vous : /tp @s 160000 310 160000");
        } catch (Exception e) {
            sender.sendMessage("Erreur : " + e);
        }
    }

    private void compact(CommandSender sender, String sub) {
        Path plans = plugin.getDataPath().resolve("plans");
        Path marker = plans.resolve("APPLY");
        try {
            if (sub.equalsIgnoreCase("cancel")) {
                sender.sendMessage(Files.deleteIfExists(marker) ? "Compaction programmee ANNULEE." : "Rien n'etait programme.");
                return;
            }
            List<Path> list = Files.isDirectory(plans) ? listPlans(plans) : List.of();
            if (list.isEmpty()) {
                sender.sendMessage("Aucun plan. Lancez d'abord /chunkdiff plan <monde> <n|all>.");
                return;
            }
            if (!sub.equalsIgnoreCase("confirm")) {
                sender.sendMessage("Plans prets : " + list.stream().map(p -> p.getFileName().toString()).toList());
                sender.sendMessage("1) Faites une sauvegarde COMPLETE de votre monde depuis le panel de votre hebergeur.");
                sender.sendMessage("2) Verifiez que vous avez assez d'espace disque : une copie des fichiers touches est faite (dossier chunkdiff-backup-*).");
                sender.sendMessage("3) Ne mettez PAS a jour Minecraft ensuite sans decompaction.");
                sender.sendMessage("Pour programmer : /chunkdiff compact confirm, puis REDEMARREZ le serveur. (/chunkdiff compact cancel pour annuler)");
                return;
            }
            Files.createDirectories(plans);
            Files.writeString(marker, "apply");
            sender.sendMessage("Compaction programmee. REDEMARREZ maintenant le serveur depuis votre panel : "
                    + "elle sera appliquee au demarrage, avant le chargement des mondes. Regardez la console au demarrage.");
        } catch (IOException e) {
            sender.sendMessage("Erreur : " + e);
        }
    }

    private static List<Path> listPlans(Path plans) throws IOException {
        try (Stream<Path> s = Files.list(plans)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".plan")).sorted().toList();
        }
    }
}
