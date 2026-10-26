package dev.chunkdiff.debug;

import dev.chunkdiff.ChunkDiffPlugin;
import dev.chunkdiff.delta.ChunkDelta;
import dev.chunkdiff.delta.DeltaStore;
import dev.chunkdiff.scan.PristineScanner;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /chunkdiff testdelta           : delta de test dans le chunk (10000,10000)
 * /chunkdiff scan [monde] [n|all] : scan lecture seule (n chunks max, defaut 500)
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
        setUsage("/chunkdiff <testdelta|scan|cancel>");
        setPermission("chunkdiff.admin");
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("testdelta")) {
            testDelta(sender);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("cancel")) {
            PristineScanner.cancelRunning(sender);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("scan")) {
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
            new PristineScanner(plugin, w, limit, sender).start();
            return true;
        }
        sender.sendMessage("Usage : /chunkdiff <testdelta|scan [monde] [n|all]|cancel>");
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
}
