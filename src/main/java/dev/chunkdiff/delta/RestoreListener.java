package dev.chunkdiff.delta;

import dev.chunkdiff.ChunkDiffPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

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
        try {
            store.load(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ())
                 .ifPresent(delta -> apply(e, delta));
        } catch (Exception ex) {
            plugin.getLogger().severe("Delta illisible " + e.getChunk() + " : " + ex);
        }
    }

    private void apply(ChunkLoadEvent e, ChunkDelta delta) {
        // TODO phase 2 : appliquer blocs, block entities, entites.
    }
}
