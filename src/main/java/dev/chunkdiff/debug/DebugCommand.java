package dev.chunkdiff.debug;

import dev.chunkdiff.ChunkDiffPlugin;
import dev.chunkdiff.delta.ChunkDelta;
import dev.chunkdiff.delta.DeltaStore;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * /chunkdiff testdelta : enregistre un delta de test pour le chunk (10000,10000) du monde principal.
 * Ce chunk est tres loin, donc jamais genere : quand on s'y teleporte, il est neuf et le delta s'applique.
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
        setDescription("ChunkDiff (debug)");
        setUsage("/chunkdiff testdelta");
        setPermission("chunkdiff.admin");
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("testdelta")) {
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
            return true;
        }
        sender.sendMessage("Usage : /chunkdiff testdelta");
        return true;
    }
}
