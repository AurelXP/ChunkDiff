package dev.chunkdiff;

import dev.chunkdiff.debug.DebugCommand;
import dev.chunkdiff.delta.DeltaStore;
import dev.chunkdiff.delta.RestoreListener;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkDiffPlugin extends JavaPlugin {
    private DeltaStore store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.store = new DeltaStore(getDataPath().resolve("deltas"));
        getServer().getPluginManager().registerEvents(new RestoreListener(this, store), this);
        getServer().getCommandMap().register("chunkdiff", new DebugCommand(this, store));
        getLogger().info("ChunkDiff actif : les chunks vanilla supprimes seront regeneres, les deltas reappliques.");
    }

    public DeltaStore store() { return store; }
}
