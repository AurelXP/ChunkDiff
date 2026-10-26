package dev.chunkdiff.delta;

import dev.chunkdiff.ChunkDiffPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.ArrayList;
import java.util.List;

/** Quand un chunk supprime est regenere par la seed, on rejoue le delta par-dessus. */
public final class RestoreListener implements Listener {
    private final ChunkDiffPlugin plugin;
    private final DeltaStore store;

    public RestoreListener(ChunkDiffPlugin plugin, DeltaStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!e.isNewChunk()) return;
        Chunk chunk = e.getChunk();
        try {
            store.load(e.getWorld().getName(), chunk.getX(), chunk.getZ())
                 .ifPresent(delta -> apply(e.getWorld(), chunk, delta));
        } catch (Exception ex) {
            plugin.getLogger().severe("Delta illisible pour le chunk " + chunk.getX() + "," + chunk.getZ() + " : " + ex);
        }
    }

    /** Phase 2 : seuls les blocs sont rejoues. Block entities et entites : phase 2b. */
    private void apply(World world, Chunk chunk, ChunkDelta delta) {
        List<BlockData> data = new ArrayList<>(delta.palette().size());
        for (String s : delta.palette()) {
            try {
                data.add(Bukkit.createBlockData(s));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Etat de bloc inconnu dans le delta : " + s);
                data.add(null);
            }
        }
        int applied = 0;
        for (ChunkDelta.BlockChange b : delta.blocks()) {
            BlockData bd = data.get(b.paletteIndex());
            if (bd == null) continue;
            if (b.y() < world.getMinHeight() || b.y() >= world.getMaxHeight()) continue;
            chunk.getBlock(b.x(), b.y(), b.z()).setBlockData(bd, false);
            applied++;
        }
        plugin.getLogger().info("Delta applique au chunk " + chunk.getX() + "," + chunk.getZ()
                + " : " + applied + " bloc(s).");
    }
}
